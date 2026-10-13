"""盘中 watch 调度器：行情 → 已收盘 bar → 信号 → 风控 → dry-run/模拟盘。"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import secrets
import time
import requests
import math
from dataclasses import asdict, replace
from datetime import datetime, timedelta
from hashlib import sha256
from pathlib import Path
from statistics import median

from .. import clock
from ..broker_client import ACTIVE_ORDER_STATUSES, TERMINAL_ORDER_STATUSES, order_remaining_qty
from ..config import PaperConfig
from ..decide import in_trading_window
from ..models import Balance, Candidate, IntentType, MxOrder, Position, QuoteValidity, SubmitRecord
from ..marketdata import (
    ActivePoolManager,
    LegacyMarketDataRouter,
    MarketDataCapability,
    MarketDataQualityEngine,
    SecurityRule,
    SecurityRuleClient,
    apply_rule_price_limits,
)
from ..mx_result import MxErrorKind
from ..risk import check_watch_intent, estimate_commission
from ..trading_calendar import TradingCalendarService, load_calendar_from_file
from .artifacts import append_daily_summary, write_tick
from .bar_store import BarStore
from .cooldown import CooldownBook
from .decide_watch import build_watch_intent
from .events import EventLog
from .execution import PreSendRejected, create_execution_adapter
from .size import sizing_detail
from .models import WatchTick
from .oms import ACTIVE_STATUSES as OMS_ACTIVE_STATUSES, LocalOrder, OmsService, OrderStatus, external_status
from .readiness import build_readiness_report
from .signal_adapter import SignalAdapter
from .state import WatchState
from .store import WatchStore, WatchStoreError, default_owner_id
from .universe import load_watch_universe


def _run_id(now: datetime) -> str:
    return now.strftime("%Y%m%dT%H%M%S") + "-" + secrets.token_hex(4)


def _result_message(result) -> str:
    if result is None:
        return "无响应结果"
    return f"code={getattr(result, 'code', '?')} kind={getattr(getattr(result, 'kind', None), 'value', '?')} msg={getattr(result, 'message', '')}"


def _exchange_for_code(code: str) -> str:
    pure = str(code).strip().zfill(6)
    if pure.startswith(("4", "8", "92")):
        return "BJ"
    if pure.startswith(("5", "6", "9")):
        return "SH"
    return "SZ"


def _hard_failed(readiness: dict) -> list:
    """返回未通过的**硬**门禁名列表（D-WARMUP-01）。

    与 ``readiness["ready"]`` 的区别：``ready`` 还受「部分就绪」展示语义影响（可能为 False
    而硬门禁全过），本函数只看硬门禁，供「是否阻断 live」这类决策使用。
    """
    return [
        str(check.get("name"))
        for check in (readiness.get("checks") or [])
        if check.get("hard") and not check.get("ok")
    ]


def _optional_float(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


class WatchLoop:
    """可运行一轮或长循环的盯盘引擎。

    构造时可注入 kline_client、clock、sleep 和 calendar，测试不会联网也不会发单。
    """

    def __init__(
        self,
        config: PaperConfig,
        quote_client,
        broker,
        *,
        kline_client=None,
        log_base: Path,
        now_fn=None,
        sleep_fn=time.sleep,
        calendar: TradingCalendarService | None = None,
        seed_bars: dict[str, list] | None = None,
        store: WatchStore | None = None,
        require_persistence: bool = False,
        store_init_error: str = "",
    ):
        self.config = config
        self.quote_client = quote_client
        self.market_data = LegacyMarketDataRouter(quote_client)
        self.broker = broker
        self.kline_client = kline_client
        self.log_base = Path(log_base)
        self.now_fn = now_fn or clock.now
        self.sleep_fn = sleep_fn
        self.bar_store = BarStore(config.bar_period_min)
        self.adapter = SignalAdapter(
            config.watch_strategy,
            ma_short=config.ma_short,
            ma_long=config.ma_long,
            boll_period=config.boll_period,
            boll_std=config.boll_std,
        )
        self._calendar = calendar
        self._calendar_explicit = calendar is not None
        self._seed_bars = seed_bars or {}
        self.store = store
        self.require_persistence = require_persistence
        self._store_init_error = store_init_error
        self._lease_owner_id = default_owner_id()
        self._lease_held = False
        self.execution_mode = config.watch_execution_mode
        self.execution_adapter = create_execution_adapter(self.execution_mode, broker)
        if store is not None:
            store.bind_account_scope(self.execution_mode, config.paper_session_id)
        self.oms = (
            OmsService(store, session_id=config.paper_session_id, channel=self.execution_mode)
            if store is not None and self.execution_mode != "SHADOW"
            else None
        )
        self._prepared_date = ""
        self._candidates: list[Candidate] = []
        self._universe_source = ""
        self._watch_pool_revision = ""
        self._prepare_errors: list[str] = []
        self._prepare_errors_pending = False
        self._disabled_codes: dict[str, str] = {}
        self._disabled_retry_at: dict[str, datetime] = {}
        self._state: WatchState | None = None
        self._cooldown = CooldownBook(config.cooldown_min)
        self._live_halted = False
        self._unknown_halt = False
        self._events: EventLog | None = None
        self._events_date = ""
        self._cycle = 0
        self._l1_quality_engine = MarketDataQualityEngine(
            missing_disable_cycles=config.quote_missing_disable_cycles,
            clock_skew_sec=config.clock_skew_sec,
        )
        self._l2_quality_engine = MarketDataQualityEngine(
            missing_disable_cycles=config.quote_missing_disable_cycles,
            clock_skew_sec=config.clock_skew_sec,
        )
        self._active_pool = ActivePoolManager(
            max_candidates=config.max_l2_codes,
            min_residence_sec=config.l2_min_residence_sec,
        )
        self._active_pool_snapshot = None
        self._latest_l1_quotes: dict[str, object] = {}
        self._latest_l1_batch = None
        self._latest_l1_quality: dict[str, dict] = {}
        self._last_l1_scan_at: datetime | None = None
        self._security_rules: dict[str, object] = {}
        self._quote_event_state: dict[str, tuple] = {}

    @property
    def calendar(self) -> TradingCalendarService:
        if self._calendar is None:
            if self.config.trading_calendar_file:
                self._calendar = load_calendar_from_file(self.config.trading_calendar_file)
                self._calendar_explicit = True
            else:
                # dry-run 可以使用周末降级；live 必须由调用方注入正式日历或文件。
                self._calendar = TradingCalendarService(source="weekday_fallback")
        return self._calendar

    def _state_path(self, trade_date: str) -> Path:
        return self.log_base / "watch" / trade_date / "state.json"

    @staticmethod
    def _percentiles(values: list[float | int]) -> dict:
        if not values:
            return {"count": 0, "p50": None, "p95": None, "p99": None, "max": None}
        ordered = sorted(float(value) for value in values)
        def pick(q: float) -> float:
            return ordered[min(round((len(ordered) - 1) * q), len(ordered) - 1)]
        return {
            "count": len(ordered),
            "p50": round(pick(0.50), 3),
            "p95": round(pick(0.95), 3),
            "p99": round(pick(0.99), 3),
            "max": round(ordered[-1], 3),
        }

    def _load_state(self, trade_date: str) -> tuple[WatchState, CooldownBook]:
        """SQLite 为主状态，兼容迁移旧 state.json。"""
        if self.store is None:
            return WatchState.load(
                self._state_path(trade_date), trade_date, self.config.cooldown_min
            )
        try:
            payload = self.store.load_runtime_state("watch_state", trade_date)
            if payload is not None:
                return WatchState.from_dict(payload, trade_date, self.config.cooldown_min)
            state, cooldown = WatchState.load(
                self._state_path(trade_date), trade_date, self.config.cooldown_min
            )
            if not state.recovery_error:
                state.cooldowns = cooldown.to_dict()
                self.store.save_runtime_state("watch_state", trade_date, state.to_dict())
                state.save(self._state_path(trade_date), cooldown)
            return state, cooldown
        except (OSError, WatchStoreError, ValueError, TypeError) as exc:
            state = WatchState(
                trade_date=trade_date,
                recovery_error=f"{type(exc).__name__}:{exc}",
            )
            return state, CooldownBook(cooldown_min=self.config.cooldown_min)

    def _persist_state(self, trade_date: str) -> None:
        """先提交权威运行库，再写 JSON 人工可读镜像。"""
        if self._state is None:
            return
        self._state.cooldowns = self._cooldown.to_dict()
        self._state.updated_at = self.now_fn().isoformat()
        if self.store is not None:
            self.store.save_runtime_state("watch_state", trade_date, self._state.to_dict())
        self._state.save(self._state_path(trade_date), self._cooldown)

    def _ensure_live_lease(self, *, renew_only: bool = False) -> tuple[bool, dict]:
        """live 单实例租约；提交前再次续租，丢失即停止发单。"""
        if self.store is None:
            return (not self.require_persistence), {"mode": "legacy_no_store"}
        try:
            if renew_only and self._lease_held:
                ok = self.store.renew_lease(
                    self._lease_owner_id, ttl_sec=self.config.lease_ttl_sec
                )
                return ok, self.store.lease_snapshot()
            ok, detail = self.store.acquire_lease(
                self._lease_owner_id,
                ttl_sec=self.config.lease_ttl_sec,
                mode="paper-live",
            )
            self._lease_held = ok
            return ok, detail
        except WatchStoreError as exc:
            return False, {"error": f"{type(exc).__name__}:{exc}"}

    def _event_log(self, trade_date: str) -> EventLog:
        # 按交易日懒加载事件流水；跨日自动轮转新文件，seq 续接。
        if self._events is None or self._events_date != trade_date or self._events._handle.closed:
            if self._events is not None:
                self._events.close()
            self._events = EventLog(self.log_base, trade_date, now_fn=self.now_fn)
            self._events_date = trade_date
        return self._events

    def prepare(self, signal_date: str, candidates_override=None, *, now: datetime | None = None) -> list[str]:
        """加载观察池、恢复状态并分批预热分钟 K 线，不混用日线 fallback。"""
        current = now or self.now_fn()
        trade_date = current.strftime("%Y-%m-%d")
        if self._prepared_date != trade_date:
            # forming bar 和策略价格序列都属于交易日内状态，不能把前一日
            # 的最后快照或 position-dependent strategy state 带到新的一天。
            self.bar_store.reset()
            self.adapter.reset()
            self._l1_quality_engine.reset()
            self._l2_quality_engine.reset()
            self._active_pool.reset()
        candidates, source, errors = load_watch_universe(
            self.config, signal_date, candidates_override, store=self.store
        )
        if (candidates_override is None and not self.config.candidate_file and not self.config.watchlist_file
                and self.config.watch_universe_mode == "T1"):
            from datetime import date
            signal_day = date.fromisoformat(signal_date)
            observation_day = signal_day + timedelta(days=1)
            for _ in range(31):
                if self.calendar.is_trading_day(observation_day):
                    break
                observation_day += timedelta(days=1)
            if (not self.calendar.available or not self._calendar_explicit and not self.config.trading_calendar_file
                    or observation_day != current.date()):
                candidates = []
                errors.append("T1_INVALID_OBSERVATION_DAY:必须使用正式日历下上一交易日的候选")
                source = "t1_invalid_day"
            if source in {"t1", "t1_no_candidate", "t1_overflow"} and self.store is not None:
                self.store.sync_default_watch_pool([
                    {"code": c.stock_code, "name": c.stock_name, "exchange": c.exchange_type} for c in candidates
                ], max_size=self.config.max_observe_codes, source="t1")
        self._candidates = candidates
        self._universe_source = source
        self._prepare_errors = list(errors)
        if self.store is not None and callable(getattr(self.store, "watch_pool_revision", None)):
            try:
                self._watch_pool_revision = self.store.watch_pool_revision()
            except WatchStoreError as exc:
                self._prepare_errors.append(
                    f"WATCH_POOL_REVISION_FAILED:{type(exc).__name__}:{exc}"
                )
        self._disabled_codes = {}
        self._disabled_retry_at = {}
        if self.store is not None and callable(getattr(self.store, "save_universe_snapshot", None)):
            universe_rows = [
                {
                    "code": item.stock_code,
                    "name": item.stock_name,
                    "exchange": item.exchange_type,
                }
                for item in sorted(candidates, key=lambda candidate: candidate.stock_code)
            ]
            material = "\n".join(
                f"{item['code']}|{item['exchange']}|{item['name']}" for item in universe_rows
            )
            try:
                self.store.save_universe_snapshot(
                    trade_date, source,
                    sha256(material.encode("utf-8")).hexdigest(), universe_rows,
                )
            except WatchStoreError as exc:
                self._prepare_errors.append(
                    f"UNIVERSE_SNAPSHOT_PERSIST_FAILED:{type(exc).__name__}:{exc}"
                )
        if self._prepared_date != trade_date:
            self._latest_l1_quotes = {}
            self._latest_l1_batch = None
            self._latest_l1_quality = {}
            self._last_l1_scan_at = None
            self._security_rules = {}
        if self._store_init_error:
            self._live_halted = True
            self._prepare_errors.append(
                f"WATCH_STORE_UNAVAILABLE:{self._store_init_error}"
            )
        self._state, self._cooldown = self._load_state(trade_date)
        if self._state.recovery_error:
            self._live_halted = True
            self._prepare_errors.append(
                f"STATE_RECOVERY_FAILED:{self._state.recovery_error}"
            )
        if self._state.unknown_submits:
            self._live_halted = True
            self._unknown_halt = True
            self._prepare_errors.append(
                f"UNKNOWN_SUBMIT_BLOCKED:{len(self._state.unknown_submits)} 条提交状态待对账"
            )

        required = self.adapter.required_bars()
        persisted_bars: dict[str, list] = {}
        forming_checkpoints: dict[str, dict] = {}
        if self.store is not None:
            try:
                persisted_bars = self.store.load_recent_closed_bars(
                    self.config.bar_period_min, max(required + 10, 80)
                )
                forming_checkpoints = self.store.load_forming_checkpoints(
                    trade_date, self.config.bar_period_min
                )
                if callable(getattr(self.store, "load_quote_quality", None)):
                    self._l1_quality_engine.restore(self.store.load_quote_quality("L1", trade_date))
                    self._l2_quality_engine.restore(self.store.load_quote_quality("L2", trade_date))
                if callable(getattr(self.store, "load_active_pool", None)):
                    self._active_pool.restore(self.store.load_active_pool(trade_date))
            except WatchStoreError as exc:
                self._live_halted = True
                self._prepare_errors.append(
                    f"WATCH_STORE_LOAD_FAILED:{type(exc).__name__}:{exc}"
                )
        prepared_bars: dict[str, list] = {}
        fetch_candidates: list[Candidate] = []

        # 先读取本地 seed/store；只有真正需要网络的标的进入并发队列，
        # 避免重启时 100 只观察标的逐只等待 K 线超时。
        for candidate in candidates:
            code = candidate.stock_code
            try:
                if code in self._seed_bars:
                    prepared_bars[code] = list(self._seed_bars[code])
                elif code in persisted_bars and (
                    source == "full_market" or len(persisted_bars[code]) >= required
                ):
                    prepared_bars[code] = list(persisted_bars[code])
                elif source == "full_market" and code in forming_checkpoints:
                    prepared_bars[code] = list(persisted_bars.get(code, []))
                elif source == "full_market":
                    # 全市场不能在启动阶段逐股请求历史 K 线；从本地历史或
                    # 实时 closed bar 渐进达到 warmup。
                    continue
                elif self.kline_client is not None:
                    fetch_candidates.append(candidate)
                else:
                    raise ValueError("未提供 KlineClient 或 seed_bars")
            except (OSError, TypeError, ValueError) as exc:
                self._disabled_codes[code] = str(exc)
                self._disabled_retry_at[code] = current + timedelta(
                    seconds=self.config.kline_retry_sec
                )
                self._prepare_errors.append(f"{code}:KLINE_DISABLED:{exc}")

        def fetch_prepare(candidate: Candidate):
            try:
                bars, result = self.kline_client.fetch_history(
                    candidate.stock_code,
                    candidate.stock_name,
                    self.config.bar_period_min,
                    max(required + 10, 80),
                    run_id=f"watch-prep-{trade_date}",
                    as_of=current,
                    exchange=candidate.exchange_type,
                )
                return candidate, bars, result, None
            except Exception as exc:  # noqa: BLE001 - 单只失败不得拖垮启动
                return candidate, None, None, exc

        deferred_candidates = fetch_candidates[self.config.kline_retry_batch_size:]
        fetch_candidates = fetch_candidates[:self.config.kline_retry_batch_size]
        if deferred_candidates:
            deferred_retry_at = current + timedelta(seconds=self.config.kline_retry_sec)
            for candidate in deferred_candidates:
                self._disabled_codes[candidate.stock_code] = "STARTUP_KLINE_DEFERRED"
                self._disabled_retry_at[candidate.stock_code] = deferred_retry_at
            self._prepare_errors.append(
                f"STARTUP_KLINE_DEFERRED:{len(deferred_candidates)} 条将在后续轮次分批预热"
            )

        if fetch_candidates:
            worker_count = min(self.config.kline_retry_workers, len(fetch_candidates))
            with ThreadPoolExecutor(
                max_workers=worker_count,
                thread_name_prefix="watch-kline-prep",
            ) as executor:
                fetched = list(executor.map(fetch_prepare, fetch_candidates))
            for candidate, bars, result, fetch_error in fetched:
                code = candidate.stock_code
                try:
                    if fetch_error is not None:
                        raise fetch_error
                    if bars is None:
                        raise ValueError(_result_message(result))
                    prepared_bars[code] = list(bars)
                except Exception as exc:  # noqa: BLE001 - 单只失败继续预热
                    self._disabled_codes[code] = str(exc)
                    self._disabled_retry_at[code] = current + timedelta(
                        seconds=self.config.kline_retry_sec
                    )
                    self._prepare_errors.append(f"{code}:KLINE_DISABLED:{exc}")

        # 网络请求完成后再统一写入 bar_store/strategy adapter，避免共享状态
        # 在工作线程中并发修改；写入阶段本身只包含本地内存操作。
        for candidate in candidates:
            code = candidate.stock_code
            if code not in prepared_bars:
                continue
            bars = prepared_bars[code]
            try:
                self.bar_store.seed(
                    code,
                    bars,
                    # 外部分钟 K 线只提供已收盘历史，首次实时快照所在桶
                    # 往往是半根；测试/离线 seed_bars 则保留原有语义。
                    discard_first_forming=(
                        code not in self._seed_bars and code not in forming_checkpoints
                    ),
                )
                checkpoint = forming_checkpoints.get(code)
                if checkpoint is not None:
                    self.bar_store.restore_forming(
                        code,
                        checkpoint["bar"],
                        last_cumulative_volume=checkpoint["last_cumulative_volume"],
                        discard_on_close=checkpoint["discard_on_close"],
                    )
                self.adapter.seed(code, self.bar_store.history(code))
                if not self.adapter.ready(code):
                    self._prepare_errors.append(f"{code}:WARMUP不足({len(bars)}/{required})")
            except (OSError, TypeError, ValueError) as exc:
                self._disabled_codes[code] = str(exc)
                self._disabled_retry_at[code] = current + timedelta(
                    seconds=self.config.kline_retry_sec
                )
                self._prepare_errors.append(f"{code}:KLINE_DISABLED:{exc}")

        if source == "full_market":
            ready = sum(1 for item in candidates if self.adapter.ready(item.stock_code))
            if ready < len(candidates):
                self._prepare_errors.append(
                    f"FULL_UNIVERSE_WARMUP:{ready}/{len(candidates)} 已就绪，"
                    f"其余标的需累计 {required} 根已收盘 bar"
                )

            # 规则主数据是独立快照：接口不可用时继续 dry-run 监控，但 live
            # 会在下方 readiness 中降级，绝不使用无来源的隐式规则冒充。
            try:
                rules, rule_errors = SecurityRuleClient(
                    self.config.t1_base, self.config.java_token
                ).fetch_all(trade_date, max_codes=self.config.max_watch_universe_codes)
                self._security_rules = rules
                self._prepare_errors.extend(rule_errors)
                if self.store is not None and callable(getattr(self.store, "save_security_rules", None)):
                    self.store.save_security_rules(
                        trade_date, (rule.to_dict() for rule in rules.values())
                    )
            except (OSError, TypeError, ValueError, requests.RequestException, WatchStoreError) as exc:
                cached_rules: list[dict] = []
                if self.store is not None and callable(getattr(self.store, "load_security_rules", None)):
                    try:
                        cached_rules = self.store.load_security_rules(trade_date)
                    except WatchStoreError:
                        cached_rules = []
                if cached_rules:
                    self._security_rules = {
                        rule.code: rule
                        for rule in (SecurityRule.from_dict(item) for item in cached_rules)
                    }
                    self._prepare_errors.append(
                        f"SECURITY_RULES_USING_SAME_DAY_CACHE:{type(exc).__name__}"
                    )
                else:
                    self._prepare_errors.append(
                        f"SECURITY_RULES_UNAVAILABLE:{type(exc).__name__}:{exc}"
                    )

        self._prepared_date = trade_date
        self._prepare_errors_pending = True
        events = self._event_log(trade_date)
        candidate_names = {candidate.stock_code: candidate.stock_name for candidate in candidates}
        for code, reason in self._disabled_codes.items():
            events.emit("ERROR", code=code, name=candidate_names.get(code, ""), detail={"scope": "KLINE_DISABLED", "message": reason}, now=current)
        if errors:
            events.emit("ERROR", detail={"scope": "UNIVERSE", "messages": errors}, now=current)
        return list(self._prepare_errors)

    def _pipeline_v2_enabled(self) -> bool:
        return bool(
            self.config.market_pipeline_v2
            and self._universe_source == "full_market"
            and MarketDataCapability.FULL_SNAPSHOT in self.market_data.capabilities()
        )

    def _l1_due(self, current: datetime) -> bool:
        if self._last_l1_scan_at is None or self._latest_l1_batch is None:
            return True
        return (current - self._last_l1_scan_at).total_seconds() >= self.config.l1_scan_interval_sec

    def _unknown_order_codes(self) -> set[str]:
        codes: set[str] = set()
        if self._state is not None:
            codes.update(
                str(item.get("stock_code", ""))
                for item in self._state.unknown_submits if item.get("stock_code")
            )
        if self.store is not None and callable(getattr(self.store, "list_local_orders", None)):
            try:
                for order in self.store.list_local_orders(
                    statuses=[OrderStatus.UNKNOWN.value, OrderStatus.CANCEL_PENDING.value],
                    channel=self.execution_mode,
                    session_id=self.config.paper_session_id,
                ):
                    if order.get("code"):
                        codes.add(str(order["code"]))
            except WatchStoreError:
                pass
        return codes

    def _warmup_codes(self, codes: set[str], current: datetime, trade_date: str, tick: WatchTick) -> None:
        if self.kline_client is None or not codes:
            return
        candidate_map = {candidate.stock_code: candidate for candidate in self._candidates}
        required = self.adapter.required_bars()
        for code in sorted(codes):
            candidate = candidate_map.get(code)
            if candidate is None or self.adapter.ready(code):
                continue
            history = self.bar_store.history(code)
            # FULL 模式启动阶段不拉全市场历史 K；活跃池标的一旦出现 1～2 根
            # 实时 closed bar，旧逻辑会因 history 非空而永久跳过补拉，导致
            # strategy_warmup 长期 0/N。历史不足时必须用公开分钟 K 补齐。
            if len(history) >= required:
                self.adapter.seed(code, history)
                continue
            try:
                bars, result = self.kline_client.fetch_history(
                    code, candidate.stock_name, self.config.bar_period_min,
                    max(required + 10, 80), run_id=f"watch-l2-prep-{trade_date}",
                    as_of=current, exchange=candidate.exchange_type,
                )
                if bars is None:
                    raise ValueError(_result_message(result))
                self.bar_store.seed(code, bars, discard_first_forming=True)
                self.adapter.seed(code, self.bar_store.history(code))
                if not self.adapter.ready(code):
                    tick.errors.append(f"{code}:L2_WARMUP不足({len(bars)}/{required})")
            except (OSError, TypeError, ValueError) as exc:
                tick.errors.append(f"{code}:L2_KLINE_DISABLED:{exc}")

    def _retry_disabled_klines(self, current: datetime, trade_date: str) -> None:
        """按退避时间有界并发重试公开 K 线预热，不阻塞整轮行情。"""
        if self.kline_client is None or not self._disabled_codes:
            return
        candidate_map = {candidate.stock_code: candidate for candidate in self._candidates}
        required = self.adapter.required_bars()
        events = self._event_log(trade_date)
        due_items: list[tuple[str, Candidate]] = []
        for code in sorted(list(self._disabled_codes)):
            retry_at = self._disabled_retry_at.get(code)
            if retry_at is not None and current < retry_at:
                continue
            candidate = candidate_map.get(code)
            if candidate is None:
                continue
            due_items.append((code, candidate))
            if len(due_items) >= self.config.kline_retry_batch_size:
                break
        if not due_items:
            return

        def fetch(item: tuple[str, Candidate]):
            code, candidate = item
            try:
                bars, result = self.kline_client.fetch_history(
                    code,
                    candidate.stock_name,
                    self.config.bar_period_min,
                    max(required + 10, 80),
                    run_id=f"watch-kline-retry-{trade_date}",
                    as_of=current,
                    exchange=candidate.exchange_type,
                )
                return code, candidate, bars, result, None
            except Exception as exc:  # noqa: BLE001 - 单只失败不得拖垮整轮
                return code, candidate, None, None, exc

        worker_count = min(self.config.kline_retry_workers, len(due_items))
        with ThreadPoolExecutor(
            max_workers=worker_count,
            thread_name_prefix="watch-kline",
        ) as executor:
            results = list(executor.map(fetch, due_items))

        # 只在主线程写 bar_store/strategy adapter/event log，避免共享状态并发写入。
        for code, candidate, bars, result, fetch_error in results:
            try:
                if fetch_error is not None:
                    raise fetch_error
                if bars is None:
                    raise ValueError(_result_message(result))
                self.bar_store.seed(code, bars, discard_first_forming=True)
                self.adapter.seed(code, self.bar_store.history(code))
                self._disabled_codes.pop(code, None)
                self._disabled_retry_at.pop(code, None)
                events.emit("ERROR", code=code, name=candidate.stock_name, cycle=self._cycle,
                            detail={
                                "scope": "KLINE_RETRY",
                                "status": "RECOVERED",
                                "bars": len(bars),
                            }, now=current)
            except Exception as exc:  # noqa: BLE001 - 单只失败继续处理其他标的
                next_retry = current + timedelta(seconds=self.config.kline_retry_sec)
                self._disabled_retry_at[code] = next_retry
                events.emit("ERROR", code=code, name=candidate.stock_name, cycle=self._cycle,
                            detail={
                                "scope": "KLINE_RETRY",
                                "status": "FAILED",
                                "retry_at": next_retry.isoformat(),
                                "message": str(exc),
                            }, now=current)

    def _persist_latest_quotes(self, quotes: dict, quality: dict[str, dict], *,
                               batch_id: str, processed_at: datetime,
                               candidates: list[Candidate] | None = None,
                               results: dict[str, tuple[object | None, object | None]] | None = None) -> None:
        if self.store is None or not callable(getattr(self.store, "upsert_latest_quotes", None)):
            return
        rows = []
        candidate_map = {candidate.stock_code: candidate for candidate in (candidates or [])}
        codes = list(candidate_map) if candidates is not None else list(quotes)
        for code in codes:
            quote = quotes.get(code)
            result = results.get(code, (None, None))[1] if results is not None else None
            q = quality.get(code, {})
            candidate = candidate_map.get(code)
            validity = getattr(getattr(quote, "validity", None), "value", "MISSING")
            quality_value = q.get("quality") or validity
            reason = q.get("reason") or getattr(quote, "error", "") or (
                _result_message(result) if quote is None else ""
            )
            rows.append({
                "trade_date": processed_at.strftime("%Y-%m-%d"),
                "code": code, "name": getattr(quote, "name", "") or getattr(candidate, "stock_name", ""),
                "exchange": getattr(quote, "exchange", "") or getattr(candidate, "exchange_type", ""),
                "last": getattr(quote, "last", 0.0),
                "pct_chg": getattr(quote, "pct_chg", None), "volume": getattr(quote, "volume", None),
                "amount": getattr(quote, "amount", None),
                "source": getattr(quote, "source", "") or (
                    "eastmoney_batch" if result is not None and "eastmoney" in str(getattr(result, "endpoint", ""))
                    else "missing"
                ),
                "market_time": quote.market_time.isoformat() if getattr(quote, "market_time", None) else "",
                "received_at": quote.received_at.isoformat() if getattr(quote, "received_at", None) else "",
                "processed_at": processed_at.isoformat(),
                "validity": validity,
                "quality": quality_value, "quality_reason": reason,
                "batch_id": batch_id, "freshness_seconds": getattr(quote, "freshness_seconds", None),
            })
        self.store.upsert_latest_quotes(rows)

    def _shadow_account(self):
        """返回本地影子账户，SHADOW 模式不得因为展示/研判消耗妙想配额。"""
        cash = max(float(self.config.internal_initial_cash), 0.0)
        return Balance(
            total_assets=cash,
            avail_balance=cash,
            acc_name="Local Shadow",
            acc_id="shadow",
            raw={"channel": "SHADOW", "source": "local_config"},
        ), [], [], "", False

    def _account(self, run_id: str):
        # SHADOW 只做本地行情研判和审计；账户/持仓/委托不应触发妙想请求。
        # INTERNAL_PAPER 自带本地 broker，也不会访问妙想。
        if self.execution_mode == "SHADOW":
            return self._shadow_account()
        balance, b_result = self.broker.balance(run_id)
        if balance is None or (b_result is not None and not b_result.ok):
            error = f"balance 查询失败: {_result_message(b_result)}"
            self._record_account_sync_error(error)
            return None, None, None, error, bool(b_result and b_result.stop_run())
        positions, p_result = self.broker.positions(run_id)
        if positions is None or (p_result is not None and not p_result.ok):
            error = f"positions 查询失败: {_result_message(p_result)}"
            self._record_account_sync_error(error)
            return None, None, None, error, bool(p_result and p_result.stop_run())
        orders, o_result = self.broker.orders(run_id)
        if orders is None or (o_result is not None and not o_result.ok):
            error = f"orders 查询失败: {_result_message(o_result)}"
            self._record_account_sync_error(error)
            return None, None, None, error, bool(o_result and o_result.stop_run())
        return balance, positions, orders, "", False

    def _record_account_sync_error(self, message: str) -> None:
        if self.store is None or self.execution_mode == "SHADOW":
            return
        try:
            self.store.save_account_sync_state({
                "channel": self.execution_mode,
                "session_id": self.config.paper_session_id,
                "source_status": "ERROR",
                "last_error": message,
            })
        except WatchStoreError:
            self._live_halted = True

    @staticmethod
    def _account_snapshot(balance, positions, orders) -> dict:
        return {
            "total_assets": balance.total_assets,
            "avail_balance": balance.avail_balance,
            "frozen_cash": balance.frozen,
            "market_value": balance.market_value,
            "total_profit": _optional_float((balance.raw or {}).get("totalProfit")),
            "total_return_pct": _optional_float((balance.raw or {}).get("totalReturnPct")),
            "day_profit": _optional_float((balance.raw or {}).get("dayProfit")),
            "day_return_pct": _optional_float((balance.raw or {}).get("dayReturnPct")),
            "positions": [
                {
                    "code": position.code,
                    "name": position.name,
                    "qty": position.qty,
                    "avail_qty": position.avail_qty,
                    "cost": position.cost,
                    "last": position.last,
                    "market_value": position.market_value,
                    "profit": position.profit,
                    "profit_rate": _optional_float((position.raw or {}).get("profitPct")),
                    "day_profit": _optional_float((position.raw or {}).get("dayProfit")),
                    "day_profit_rate": _optional_float((position.raw or {}).get("dayProfitPct")),
                    "position_pct": _optional_float((position.raw or {}).get("positionPct")),
                    "exchange": _exchange_for_code(position.code),
                    "price_source": "MX_ACCOUNT_SNAPSHOT"
                        if (position.raw or {}).get("channel") != "INTERNAL_PAPER"
                        else "INTERNAL_MATCHING",
                }
                for position in positions
            ],
            "orders": [
                {
                    "order_id": order.order_id,
                    "code": order.code,
                    "side": order.side,
                    "price": order.price,
                    "qty": order.qty,
                    "filled_qty": order.filled_qty,
                    "status": order.status,
                    "ts": order.ts,
                }
                for order in orders
            ],
        }

    @classmethod
    def _account_event_detail(cls, balance, positions, orders, *, phase: str, **extra) -> dict:
        """账户事件保留汇总字段，同时携带当前持仓和在途委托明细供监控面板展示。"""
        snapshot = cls._account_snapshot(balance, positions, orders)
        return {
            "phase": phase,
            "total_assets": snapshot["total_assets"],
            "avail_balance": snapshot["avail_balance"],
            "market_value": snapshot["market_value"],
            "positions": len(snapshot["positions"]),
            "open_orders": sum(
                1 for order in orders
                if external_status(order).value in OMS_ACTIVE_STATUSES
                and max(int(order.qty) - int(order.filled_qty or 0), 0) > 0
            ),
            "position_details": snapshot["positions"],
            "order_details": snapshot["orders"],
            **extra,
        }

    def _persist_account_snapshot(
        self,
        *,
        run_id: str,
        phase: str,
        trade_date: str,
        balance,
        positions,
        orders,
        captured_at: datetime,
    ) -> None:
        if self.store is None:
            return
        self.store.save_account_snapshot({
            "snapshot_id": f"{self.execution_mode}:{self.config.paper_session_id}:{run_id}:{phase}",
            "session_id": self.config.paper_session_id,
            "channel": self.execution_mode,
            "trade_date": trade_date,
            "captured_at": captured_at.isoformat(),
            "consistent": True,
            "payload": self._account_snapshot(balance, positions, orders),
        })

    @staticmethod
    def _reserve_buy_commitment(balance, orders: list[MxOrder], intent, order_id: str) -> None:
        """把本轮已放行买单计入后续风控投影，避免沿用轮初资金/仓位。"""
        gross = intent.qty * float(intent.limit_price)
        balance.avail_balance = max(
            float(balance.avail_balance) - gross - estimate_commission(gross),
            0.0,
        )
        orders.append(MxOrder(
            order_id=order_id,
            code=intent.stock_code,
            side="buy",
            price=intent.limit_price,
            qty=intent.qty,
            filled_qty=0,
            status="2",
        ))

    def _warmup_positions(self, positions: list[Position], current: datetime, trade_date: str, tick: WatchTick) -> None:
        """为已有持仓定向预热，避免卖出信号永远等待 20 根 bar。"""
        if self.kline_client is None:
            return
        candidate_map = {candidate.stock_code: candidate for candidate in self._candidates}
        required = self.adapter.required_bars()
        for position in positions:
            candidate = candidate_map.get(position.code)
            if candidate is None or self.adapter.ready(position.code):
                continue
            history = self.bar_store.history(position.code)
            if len(history) >= required:
                self.adapter.seed(position.code, history)
                continue
            try:
                bars, result = self.kline_client.fetch_history(
                    position.code,
                    candidate.stock_name,
                    self.config.bar_period_min,
                    max(required + 10, 80),
                    run_id=f"watch-position-prep-{trade_date}",
                    as_of=current,
                    exchange=candidate.exchange_type,
                )
                if bars is None:
                    raise ValueError(_result_message(result))
                self.bar_store.seed(position.code, bars, discard_first_forming=True)
                self.adapter.seed(position.code, self.bar_store.history(position.code))
                if not self.adapter.ready(position.code):
                    tick.errors.append(f"{position.code}:{candidate.stock_name}:WARMUP不足({len(bars)}/{required})")
            except (OSError, TypeError, ValueError) as exc:
                tick.errors.append(f"{position.code}:{candidate.stock_name}:POSITION_KLINE_DISABLED:{exc}")

    def _ensure_account_candidates(
        self,
        positions: list[Position],
        orders: list[MxOrder],
        signal_date: str,
        current: datetime,
        trade_date: str,
        tick: WatchTick,
    ) -> set[str]:
        """持仓和活动委托是强制监控对象，不能因观察池修改而失联。"""
        position_by_code = {
            str(position.code): position
            for position in positions
            if int(getattr(position, "qty", 0) or 0) > 0
        }
        mandatory_codes = set(position_by_code)
        mandatory_codes.update(
            str(order.code)
            for order in orders
            if order.status in ACTIVE_ORDER_STATUSES and order_remaining_qty(order) > 0
        )
        mandatory_codes.update(self._unknown_order_codes())
        candidate_codes = {candidate.stock_code for candidate in self._candidates}
        added = sorted(code for code in mandatory_codes if code and code not in candidate_codes)
        if not added:
            return set()

        for code in added:
            position = position_by_code.get(code)
            self._candidates.append(Candidate(
                stock_code=code,
                stock_name=str(getattr(position, "name", "") or code),
                exchange_type=_exchange_for_code(code),
                signal_date=signal_date or trade_date,
                source="mandatory_account",
            ))
        tick.errors.append("ACCOUNT_SECURITIES_FORCED_INTO_UNIVERSE:" + ",".join(added))
        self._warmup_codes(set(added), current, trade_date, tick)
        return set(added)

    def _enforce_execution_freshness(self, quote, checked_at: datetime):
        """外部提交前按执行级 SLA 重算年龄，不复用常规轮询的 120 秒阈值。"""
        if quote is None:
            return None
        quote.received_at = checked_at
        if quote.market_time is None:
            quote.validity = QuoteValidity.INVALID
            quote.error = "MARKET_TIME_UNAVAILABLE"
            quote.freshness_seconds = None
            return quote
        age = (checked_at - quote.market_time).total_seconds()
        if age < -self.config.clock_skew_sec:
            quote.validity = QuoteValidity.INVALID
            quote.error = "FUTURE_TIMESTAMP"
            quote.freshness_seconds = None
            return quote
        quote.freshness_seconds = max(age, 0.0)
        if age > self.config.execution_quote_stale_sec:
            quote.validity = QuoteValidity.STALE
            quote.error = (
                f"EXECUTION_QUOTE_STALE:{age:.1f}s>"
                f"{self.config.execution_quote_stale_sec}s"
            )
        elif quote.validity in {QuoteValidity.VALID, QuoteValidity.STALE} or quote.error == "FUTURE_TIMESTAMP":
            quote.validity = QuoteValidity.VALID
            if quote.error.startswith(("FUTURE_TIMESTAMP", "EXECUTION_QUOTE_STALE")):
                quote.error = ""
        return quote

    @staticmethod
    def _order_id(result) -> str:
        data = getattr(result, "data", None)
        if isinstance(data, dict):
            for key in ("orderId", "order_id", "id"):
                if data.get(key):
                    return str(data[key])
        return ""

    def _reconcile_unknown_submits(self, orders: list[MxOrder], trade_date: str) -> list[str]:
        """只用可核验的活动委托解除 unknown；没有匹配时继续冻结。"""
        if self._state is None or not self._state.unknown_submits:
            return []
        original_pending = list(self._state.unknown_submits)
        remaining: list[dict] = []
        messages: list[str] = []
        used_order_ids: set[str] = set()
        # 本进程提交成功过的 order_id。UNKNOWN_SUBMIT 一定没有可信 order_id，
        # 故凡是已在册的委托都不可能是这次未知提交本身，只能是对账前既有的同参
        # 历史单（同日同标的同向同量同价是常态）—— 认领它会以「旧单已成交/已撤」
        # 为证据错误解除冻结。
        known_order_ids = set(self._state.open_order_ids)
        for pending in self._state.unknown_submits:
            code = str(pending.get("stock_code", ""))
            side = str(pending.get("side", ""))
            try:
                qty = int(pending.get("qty", 0) or 0)
            except (TypeError, ValueError):
                # 损坏的 unknown 记录也必须保持冻结，不能因为对账代码
                # 自身异常而把 live 放行。
                remaining.append(pending)
                continue
            try:
                expected_price = float(pending.get("price"))
            except (TypeError, ValueError):
                expected_price = None
            match = None
            filled_terminal: MxOrder | None = None
            for order in orders:
                if order.order_id in used_order_ids or order.order_id in known_order_ids:
                    continue
                if (
                    order.code != code
                    or order.side != side
                    or order.qty != qty
                ):
                    continue
                if expected_price is None or order.price is None:
                    continue
                if abs(float(order.price) - expected_price) > 0.02:
                    continue
                # 「已到达柜台」的证据有两类：仍在活动的委托，以及已终态且零成交的
                # 委托（废单/全撤）。后者此前被判为不匹配 —— 于是一笔已经了结、
                # 不再变化的委托反而和「柜台根本没收到」一样永久冻结实盘。
                # 已终态但带成交的委托同样证明请求到达了柜台，但成交未被本进程
                # 记账过，需人工核对，故只记录不解除冻结。
                if order.status in ACTIVE_ORDER_STATUSES and order_remaining_qty(order) > 0:
                    match = order
                    break
                if order.status in TERMINAL_ORDER_STATUSES:
                    if int(order.filled_qty or 0) <= 0:
                        match = order
                        break
                    if filled_terminal is None:
                        filled_terminal = order
            if match is None:
                if filled_terminal is not None:
                    messages.append(
                        f"UNKNOWN_SUBMIT_FILLED_NEEDS_MANUAL:{code}/{side}/{qty} "
                        f"-> {filled_terminal.order_id} filled={int(filled_terminal.filled_qty or 0)}"
                    )
                remaining.append(pending)
                continue
            used_order_ids.add(match.order_id)
            if match.order_id not in self._state.open_order_ids:
                self._state.open_order_ids.append(match.order_id)
            messages.append(
                f"UNKNOWN_SUBMIT_RECONCILED:{code}/{side}/{qty} -> {match.order_id}"
            )
        changed = len(remaining) != len(self._state.unknown_submits)
        self._state.unknown_submits = remaining
        if changed:
            try:
                self._persist_state(trade_date)
            except (OSError, WatchStoreError) as exc:
                # 未能持久化“已对账”证据时不能只在内存中解除冻结；重启后
                # 状态仍未知，继续保持原 unknown 集合和 live halt。
                self._state.unknown_submits = original_pending
                self._live_halted = True
                messages.append(f"STATE_PERSIST_FAILED:{type(exc).__name__}")
                return messages
        if remaining:
            self._live_halted = True
        elif self._unknown_halt:
            # 只有本次 halt 来自已核验的 unknown 提交时才解除；其他停止性
            # 错误仍由调用方维持 fail-closed 状态。
            self._unknown_halt = False
            self._live_halted = False
        return messages

    def _submit(self, intent, run_id: str, local_order: LocalOrder | None = None) -> SubmitRecord:
        try:
            result = self.execution_adapter.submit_order(
                intent, run_id=run_id, local_order=local_order
            )
        except PreSendRejected as exc:
            # 发送前确定性拒单：服务端从未受理，既不是 unknown（无需对账、不冻结
            # live），也不是网关拒单。按 REJECTED 记录并让循环继续。
            return SubmitRecord(
                stock_code=intent.stock_code,
                side=intent.side,
                qty=intent.qty,
                price=intent.limit_price,
                ok=False,
                status="REJECTED",
                error_code=exc.code,
                error_message=exc.message,
            )
        except Exception as exc:
            return SubmitRecord(
                stock_code=intent.stock_code,
                side=intent.side,
                qty=intent.qty,
                price=intent.limit_price,
                ok=False,
                status="UNKNOWN_SUBMIT",
                error_code="CLIENT_EXCEPTION",
                error_message=type(exc).__name__,
            )
        order_id = self._order_id(result)
        ok = bool(result is not None and result.ok and order_id)
        if ok:
            return SubmitRecord(
                stock_code=intent.stock_code,
                side=intent.side,
                qty=intent.qty,
                price=intent.limit_price,
                ok=True,
                order_id=order_id,
                status="SUBMITTED",
                request_id=getattr(result, "request_id", ""),
                raw=getattr(result, "raw", {}) if isinstance(getattr(result, "raw", {}), dict) else {},
            )
        if result is None:
            fail_status, fail_code = "UNKNOWN_SUBMIT", "NO_RESPONSE"
        elif result.ok and not order_id:
            fail_status, fail_code = "UNKNOWN_SUBMIT", "NO_ORDER_ID"
        elif (
            getattr(result, "kind", None) in {MxErrorKind.HTTP_ERROR, MxErrorKind.PARSE_ERROR}
            or str(getattr(getattr(result, "kind", None), "value", getattr(result, "kind", "")))
            in {MxErrorKind.HTTP_ERROR.value, MxErrorKind.PARSE_ERROR.value}
        ):
            # trade 禁止自动重试；网络/解析异常无法证明服务端未受理，
            # 必须按 unknown 处理并在 orders 对账前冻结，不能误当拒单再下。
            fail_status = "UNKNOWN_SUBMIT"
            fail_code = getattr(getattr(result, "kind", None), "value", str(getattr(result, "kind", "")))
        else:
            fail_status, fail_code = "REJECTED", str(getattr(result, "code", ""))
        return SubmitRecord(
            stock_code=intent.stock_code,
            side=intent.side,
            qty=intent.qty,
            price=intent.limit_price,
            ok=False,
            order_id=order_id,
            status=fail_status,
            error_code=fail_code,
            error_message=str(getattr(result, "message", "提交失败或缺少 orderId")),
            request_id=getattr(result, "request_id", ""),
            raw=getattr(result, "raw", {}) if isinstance(getattr(result, "raw", {}), dict) else {},
        )

    def _sync_oms(self, orders: list[MxOrder], current: datetime, tick: WatchTick) -> None:
        if self.oms is None or self.execution_mode != "EXTERNAL_PAPER":
            return
        try:
            changes = self.oms.sync_external(orders, current)
            if changes:
                tick.metrics["oms_sync_changes"] = changes
                events = self._event_log(tick.trade_date)
                for change in changes:
                    events.emit("ORDER", code="", cycle=self._cycle, detail={
                        "phase": "sync", **change,
                    }, now=current)
        except WatchStoreError as exc:
            self._live_halted = True
            tick.live = False
            tick.errors.append(f"OMS_SYNC_FAILED:{type(exc).__name__}:{exc}")

    def _oms_unknown_count(self) -> int:
        if self.oms is None:
            return 0
        try:
            return sum(
                1 for order in self.oms.active()
                if order.status in {OrderStatus.SUBMITTING.value, OrderStatus.UNKNOWN.value}
            )
        except WatchStoreError:
            self._live_halted = True
            return 1

    def _oms_active_count(self) -> int:
        if self.oms is None:
            return 0
        try:
            return len(self.oms.active())
        except WatchStoreError:
            self._live_halted = True
            return 0

    def _expire_orders(self, current: datetime, run_id: str, tick: WatchTick) -> None:
        """TTL 到期先基于本轮 orders 同步，再发一次撤单；不追单、不重试。"""
        if self.oms is None or not self.config.auto_cancel_expired:
            return
        events = self._event_log(tick.trade_date)
        for order in self.oms.active():
            try:
                expires_at = datetime.fromisoformat(order.expires_at)
            except ValueError:
                self._live_halted = True
                tick.errors.append(f"OMS_EXPIRES_INVALID:{order.client_order_id}")
                continue
            if current < expires_at:
                continue
            if order.status == OrderStatus.CREATED.value:
                self.oms.transition(order.client_order_id, OrderStatus.EXPIRED)
                events.emit("ORDER", code=order.code, cycle=self._cycle, detail={
                    "phase": "expired_before_submit", "client_order_id": order.client_order_id,
                    "status": OrderStatus.EXPIRED.value,
                }, now=current)
                continue
            if order.status == OrderStatus.SUBMITTING.value:
                self.oms.transition(
                    order.client_order_id,
                    OrderStatus.UNKNOWN,
                    last_error_code="SUBMITTING_RECOVERY",
                    last_error_message="进程中断窗口无法证明 trade 是否已调用",
                )
                self._live_halted = True
                tick.live = False
                tick.errors.append(f"SUBMITTING_RECOVERY_UNKNOWN:{order.client_order_id}")
                continue
            if order.status not in {OrderStatus.SUBMITTED.value, OrderStatus.PARTIAL.value}:
                continue
            try:
                pending = self.oms.transition(order.client_order_id, OrderStatus.CANCEL_PENDING)
                result = self.execution_adapter.cancel_order(
                    pending.broker_order_id or pending.client_order_id,
                    pending.code,
                    run_id=run_id,
                )
                # 内部 Broker 在同一事务中已确认撤销；外部只记请求已发送，
                # 必须等待下一轮 orders 证明最终状态。
                status = (
                    self.oms.get(order.client_order_id).status
                    if not self.execution_adapter.supports_external_execution
                    else OrderStatus.CANCEL_PENDING.value
                )
                events.emit("CANCEL", code=order.code, cycle=self._cycle, detail={
                    "client_order_id": order.client_order_id,
                    "broker_order_id": order.broker_order_id,
                    "ok": bool(result and result.ok),
                    "status": status,
                    "message": str(getattr(result, "message", "")),
                }, now=current)
                if result is None or not result.ok:
                    self.oms.transition(
                        order.client_order_id,
                        OrderStatus.UNKNOWN,
                        last_error_code=str(getattr(result, "code", "NO_RESPONSE")),
                        last_error_message=str(getattr(result, "message", "撤单结果未知")),
                    )
                    self._live_halted = True
                    tick.live = False
                    tick.errors.append(f"CANCEL_UNKNOWN:{order.client_order_id}")
            except (OSError, ValueError, WatchStoreError) as exc:
                self._live_halted = True
                tick.live = False
                tick.errors.append(f"CANCEL_FAILED:{order.client_order_id}:{type(exc).__name__}:{exc}")

    def close(self) -> None:
        """释放租约、运行库与事件流水句柄。"""
        if self.store is not None:
            if self._lease_held:
                try:
                    self.store.release_lease(self._lease_owner_id)
                except WatchStoreError:
                    pass
                self._lease_held = False
            self.store.close()
        if self._events is not None:
            self._events.close()
            self._events = None

    def _finish(self, tick: WatchTick) -> WatchTick:
        tick.finished_at = self.now_fn().isoformat()
        if self.store is not None:
            try:
                self.store.save_cycle_metric(tick.to_dict())
            except WatchStoreError as exc:
                tick.errors.append(f"CYCLE_METRIC_PERSIST_FAILED:{type(exc).__name__}:{exc}")
                if tick.live:
                    self._live_halted = True
                    tick.live = False
                    tick.status = "BLOCKED"
        write_tick(self.log_base, tick)
        append_daily_summary(self.log_base, tick)
        # 所有退出路径（含 BLOCKED/SLEEPING 提前返回）都补记 CYCLE end，
        # 否则面板「最近状态」停留在上一次正常轮。
        if self._events is not None:
            self._events.emit("CYCLE", cycle=self._cycle, detail={
                "phase": "end", "run_id": tick.run_id, "status": tick.status, "live": tick.live,
                "execution_mode": self.execution_mode,
                "bars": len(tick.new_closed_bars), "signals": len(tick.signals),
                "submits": len(tick.submits), "errors": len(tick.errors),
                "metrics": tick.metrics,
            }, now=self.now_fn())
        return tick

    def run_once(
        self,
        signal_date: str,
        *,
        live: bool = False,
        confirm: bool = False,
        candidates_override=None,
        now: datetime | None = None,
    ) -> WatchTick:
        current = now or self.now_fn()
        trade_date = current.strftime("%Y-%m-%d")
        run_id = _run_id(current)
        self._cycle += 1
        events = self._event_log(trade_date)
        events.emit("CYCLE", cycle=self._cycle, detail={
            "phase": "start", "run_id": run_id, "live_requested": bool(live),
            "execution_mode": self.execution_mode,
        }, now=current)
        actual_live = bool(live and confirm and self.config.watch_live and not self._live_halted)
        if self.execution_mode == "SHADOW":
            actual_live = False
        tick = WatchTick(
            run_id=run_id,
            trade_date=trade_date,
            started_at=current.isoformat(),
            finished_at=current.isoformat(),
            live=actual_live,
        )
        tick.metrics["execution_mode"] = self.execution_mode

        if live and (not confirm or not self.config.watch_live):
            tick.errors.append("LIVE_CONFIRM_REQUIRED: --live、--confirm 与 PAPER_WATCH_LIVE=1 必须同时满足")
            tick.status = "BLOCKED"
            return self._finish(tick)
        if live and self.execution_mode == "SHADOW":
            tick.errors.append("SHADOW_NO_SUBMIT: 仅生成信号、风控与订单意图")
        # INTERNAL_PAPER 是本地模拟，不会触达外部交易柜台，可以使用本地
        # weekday fallback；EXTERNAL_PAPER 仍必须注入正式交易日历。
        if (
            live
            and self.execution_mode == "EXTERNAL_PAPER"
            and not self._calendar_explicit
            and not self.config.trading_calendar_file
        ):
            tick.errors.append("TRADING_CALENDAR_REQUIRED: live 必须注入交易日历文件或服务")
            tick.status = "BLOCKED"
            return self._finish(tick)

        if self._prepared_date != trade_date:
            try:
                self.prepare(signal_date or trade_date, candidates_override, now=current)
            except (OSError, TypeError, ValueError) as exc:
                tick.errors.append(f"UNIVERSE_PREPARE_FAILED:{type(exc).__name__}:{exc}")
                tick.status = "BLOCKED"
                return self._finish(tick)
        elif (
            candidates_override is None
            and not self.config.watchlist_file
            and not self.config.candidate_file
            and self.config.watch_universe_mode == "WATCHLIST"
            and self.store is not None
            and callable(getattr(self.store, "watch_pool_revision", None))
        ):
            # watch-serve 与盯盘循环共享 SQLite。页面人工增删后，下一轮
            # 重新加载并预热观察池，无需重启长循环。
            try:
                revision = self.store.watch_pool_revision()
                if revision != self._watch_pool_revision:
                    self.prepare(signal_date or trade_date, None, now=current)
            except WatchStoreError as exc:
                tick.errors.append(
                    f"WATCH_POOL_REFRESH_FAILED:{type(exc).__name__}:{exc}"
                )
        if self._pipeline_v2_enabled():
            universe_material = "\n".join(
                f"{item.stock_code}|{item.exchange_type}|{item.stock_name}"
                for item in sorted(self._candidates, key=lambda candidate: candidate.stock_code)
            )
            tick.universe = [{
                "universe_hash": sha256(universe_material.encode("utf-8")).hexdigest(),
                "count": len(self._candidates),
                "source": self._universe_source,
            }]
        else:
            tick.universe = [asdict(candidate) for candidate in self._candidates]
        if self._prepare_errors_pending:
            tick.errors.extend(self._prepare_errors)
            self._prepare_errors_pending = False
        if live and "T1_UNAVAILABLE" in self._prepare_errors:
            tick.errors.append("DATA_SOURCE_BLOCKED:T1 观察池不可用，live 不允许空名单继续")
            tick.status = "BLOCKED"
            return self._finish(tick)
        if live and self.require_persistence and self.execution_mode == "SHADOW":
            tick.errors.append(
                "EXECUTION_MODE_BLOCKED:PAPER_WATCH_EXECUTION_MODE=SHADOW 不允许提交；"
                "请选择 INTERNAL_PAPER 或 EXTERNAL_PAPER"
            )
            tick.live = False
            tick.status = "BLOCKED"
            return self._finish(tick)
        if live and any(
            error.startswith(("FULL_UNIVERSE_UNAVAILABLE", "FULL_UNIVERSE_TRUNCATED"))
            for error in self._prepare_errors
        ):
            tick.errors.append("DATA_SOURCE_BLOCKED:全量股票主数据不可用或不完整，live 不允许继续")
            tick.status = "BLOCKED"
            return self._finish(tick)
        if live and any(error.startswith("STATE_RECOVERY_FAILED") for error in self._prepare_errors):
            tick.errors.append("STATE_RECOVERY_BLOCKED:交易状态无法可靠恢复，live 禁止继续")
            tick.live = False
            tick.status = "BLOCKED"
            return self._finish(tick)
        if live and any(
            error.startswith(("WATCH_STORE_UNAVAILABLE", "WATCH_STORE_LOAD_FAILED"))
            for error in self._prepare_errors
        ):
            tick.errors.append("WATCH_STORE_BLOCKED:运行库不可用或状态加载失败，live 禁止继续")
            tick.live = False
            tick.status = "BLOCKED"
            return self._finish(tick)
        # 真实/内部账户即使人工观察池为空，也必须继续查询账户；已有持仓和
        # 活动委托会在账户快照后被强制并入候选。SHADOW 没有外部账户可补充。
        tick.metrics["universe_source"] = self._universe_source
        tick.metrics["universe_count"] = len(self._candidates)
        tick.metrics["selection_signal_date"] = signal_date
        if not self._candidates and self.execution_mode == "SHADOW":
            tick.status = "NO_UNIVERSE"
            return self._finish(tick)
        if not in_trading_window(current):
            tick.status = "SLEEPING"
            return self._finish(tick)
        try:
            is_trading_day = self.calendar.is_trading_day(current)
        except (OSError, TypeError, ValueError) as exc:
            tick.errors.append(f"TRADING_CALENDAR_ERROR:{type(exc).__name__}:{exc}")
            if live:
                self._live_halted = True
            tick.status = "BLOCKED"
            return self._finish(tick)
        if not is_trading_day:
            tick.errors.append(f"NOT_TRADING_DAY:{trade_date}")
            tick.status = "SLEEPING"
            return self._finish(tick)
        lease_detail: dict = {"mode": "dry-run"}
        lease_ok = True
        if actual_live and self.require_persistence:
            lease_ok, lease_detail = self._ensure_live_lease()
            if not lease_ok:
                # 本地 INTERNAL_PAPER 遇到旧进程遗留租约时只阻断当前轮，
                # 下一轮继续尝试；外部模拟盘仍保持 fail-closed。
                if self.execution_mode != "INTERNAL_PAPER":
                    self._live_halted = True
                tick.live = False
                tick.errors.append("LIVE_LEASE_BLOCKED:已有活动实例或运行库不可用")
                events.emit("ERROR", cycle=self._cycle, detail={
                    "scope": "LIVE_LEASE", "message": tick.errors[-1],
                    "lease": lease_detail,
                }, now=current)
                store_health = self.store.health() if self.store is not None else {
                    "ok": False, "error": self._store_init_error or "store missing"
                }
                tick.metrics["readiness"] = build_readiness_report(
                    store_health=store_health,
                    store_path=self.store.path if self.store is not None else self.log_base,
                    lease_ok=False,
                    state_ok=bool(self._state is not None and not self._state.recovery_error),
                    universe_count=len(self._candidates),
                    strategy_ready_count=sum(
                        1 for item in self._candidates if self.adapter.ready(item.stock_code)
                    ),
                    min_strategy_ready_coverage=self.config.min_strategy_ready_coverage,
                    min_store_free_mb=self.config.min_store_free_mb,
                )
                tick.metrics["readiness"]["lease"] = lease_detail
                tick.status = "BLOCKED"
                return self._finish(tick)
        if self._live_halted:
            tick.errors.append("LIVE_DISABLED_AFTER_STOP_RUN: 后续仅 dry-run，不再提交")
            actual_live = False
            tick.live = False

        balance, positions, orders, account_error, account_stop = self._account(run_id)
        if account_error:
            tick.errors.append(account_error)
            if live:
                self._live_halted = True
                tick.live = False
                tick.errors.append(
                    "STOP_RUN: 账户查询失败，状态未知，后续切换 dry-run"
                    + ("（停止性错误）" if account_stop else "")
                )
            tick.status = "BLOCKED"
            return self._finish(tick)
        tick.account = self._account_snapshot(balance, positions, orders)
        self._sync_oms(orders, current, tick)
        if actual_live:
            self._expire_orders(current, run_id, tick)
        if getattr(self.broker, "is_internal_paper", False):
            # 撤单可能释放内部冻结资金/可卖数量，重新获取一致账户视图。
            balance, positions, orders, account_error, _ = self._account(run_id)
            if account_error:
                tick.errors.append("INTERNAL_ACCOUNT_REFRESH_FAILED:" + account_error)
                tick.status = "BLOCKED"
                return self._finish(tick)
            tick.account = self._account_snapshot(balance, positions, orders)
        try:
            self._persist_account_snapshot(
                run_id=run_id, phase="before", trade_date=trade_date,
                balance=balance, positions=positions, orders=orders, captured_at=current,
            )
        except WatchStoreError as exc:
            self._live_halted = True
            tick.live = False
            tick.errors.append(f"ACCOUNT_SNAPSHOT_PERSIST_FAILED:{type(exc).__name__}:{exc}")
            if actual_live:
                tick.status = "BLOCKED"
                return self._finish(tick)
        forced_account_codes = self._ensure_account_candidates(
            positions, orders, signal_date, current, trade_date, tick
        )
        if forced_account_codes:
            if self._pipeline_v2_enabled():
                universe_material = "\n".join(
                    f"{item.stock_code}|{item.exchange_type}|{item.stock_name}"
                    for item in sorted(
                        self._candidates, key=lambda candidate: candidate.stock_code
                    )
                )
                tick.universe = [{
                    "universe_hash": sha256(universe_material.encode("utf-8")).hexdigest(),
                    "count": len(self._candidates),
                    "source": self._universe_source,
                    "forced_account_codes": sorted(forced_account_codes),
                }]
            else:
                tick.universe = [asdict(candidate) for candidate in self._candidates]
        if forced_account_codes and self._pipeline_v2_enabled():
            # FULL 模式的规则快照在 prepare 阶段获取。临时强制加入但没有规则
            # 的持仓仍会被后续 security-rule 闸门 fail-closed；行情监控不能缺席。
            self._prepare_errors.append(
                "ACCOUNT_SECURITIES_OUTSIDE_MASTER:" + ",".join(sorted(forced_account_codes))
            )
        if not self._candidates:
            tick.status = "NO_UNIVERSE"
            return self._finish(tick)
        self._warmup_positions(positions, current, trade_date, tick)
        for message in self._reconcile_unknown_submits(orders, trade_date):
            tick.errors.append(message)
            events.emit("ACCOUNT", cycle=self._cycle, detail={
                "phase": "reconcile_unknown", "message": message,
            }, now=current)
        if self._state is not None and self._state.unknown_submits:
            tick.errors.append(
                f"UNKNOWN_SUBMIT_PENDING:{len(self._state.unknown_submits)} 条提交待人工/柜台对账"
            )
        oms_unknown_count = self._oms_unknown_count()
        if oms_unknown_count:
            tick.errors.append(f"OMS_UNKNOWN_PENDING:{oms_unknown_count} 条本地订单待柜台核验")
        events.emit("ACCOUNT", cycle=self._cycle, detail={
            **self._account_event_detail(balance, positions, orders, phase="before"),
        }, now=current)
        position_map = {position.code: position for position in positions}
        candidate_codes = {candidate.stock_code for candidate in self._candidates}

        # 观察池中的 K 线预热失败可能只是公开接口瞬时网络错误；到期后
        # 重试，成功后本轮继续走行情、bar 和策略链路。
        self._retry_disabled_klines(current, trade_date)
        pipeline_v2 = self._pipeline_v2_enabled()
        l1_scanned = False
        if pipeline_v2 and self._l1_due(current):
            try:
                l1_batch = self.market_data.snapshot_all(
                    self._candidates, run_id=run_id, received_at=current
                )
                # 质量评估用「评估时刻」而非轮次起点，否则长耗时 L1 扫描后半段
                # 会被 FUTURE_MARKET_TIME 误杀。
                self._latest_l1_quality = self._l1_quality_engine.assess_batch(
                    l1_batch, now=self.now_fn()
                )
                self._latest_l1_quotes = dict(l1_batch.quotes)
                self._latest_l1_batch = l1_batch
                self._last_l1_scan_at = current
                l1_scanned = True
                summary = l1_batch.to_summary()
                summary["trade_date"] = trade_date
                if self.store is not None and callable(getattr(self.store, "save_market_data_batch", None)):
                    self.store.save_market_data_batch(
                        summary, self._latest_l1_quality.values()
                    )
                self._persist_latest_quotes(
                    self._latest_l1_quotes, self._latest_l1_quality,
                    batch_id=l1_batch.batch_id, processed_at=current,
                )
                events.emit("MARKET_DATA", cycle=self._cycle, detail={
                    "phase": "L1_BATCH", **summary,
                }, now=current)
            except (OSError, TypeError, ValueError, WatchStoreError, requests.RequestException) as exc:
                tick.errors.append(f"L1_MARKET_SCAN_FAILED:{type(exc).__name__}:{exc}")
                events.emit("ERROR", cycle=self._cycle, detail={
                    "scope": "L1_MARKET_DATA", "message": tick.errors[-1],
                }, now=current)

        if pipeline_v2:
            self._active_pool_snapshot = self._active_pool.refresh(
                candidates=self._candidates,
                quotes=self._latest_l1_quotes,
                positions=positions,
                orders=orders,
                unknown_codes=self._unknown_order_codes(),
                now=current,
                excluded_codes={
                    item.stock_code for item in self._candidates
                    if self._l2_quality_engine.is_disabled(item.stock_code)
                },
            )
            # 必须对整个活跃池补预热，而不能只处理 promoted：
            # FULL 启动不拉全市场 K 线，池内旧成员若只有少量实时 bar，
            # 仅暖新晋标的会让 strategy_warmup 长期停在 0~1/N。
            self._warmup_codes(
                set(self._active_pool_snapshot.codes),
                current, trade_date, tick,
            )
            if self.store is not None and callable(getattr(self.store, "save_active_pool", None)):
                try:
                    self.store.save_active_pool(
                        trade_date, self._active_pool_snapshot.rows()
                    )
                except WatchStoreError as exc:
                    tick.errors.append(f"ACTIVE_POOL_PERSIST_FAILED:{type(exc).__name__}:{exc}")
            if self._active_pool_snapshot.promoted or self._active_pool_snapshot.demoted:
                events.emit("ACTIVE_POOL", cycle=self._cycle, detail={
                    "active_count": len(self._active_pool_snapshot.codes),
                    "mandatory_count": len(self._active_pool_snapshot.mandatory_codes),
                    "promoted": sorted(self._active_pool_snapshot.promoted),
                    "demoted": sorted(self._active_pool_snapshot.demoted),
                    "capacity_exceeded": self._active_pool_snapshot.capacity_exceeded,
                }, now=current)
            if self._active_pool_snapshot.capacity_exceeded:
                tick.errors.append("L2_CAPACITY_EXCEEDED:强制标的数量超过配置容量，禁止新增开仓")

        strategy_ready_count = sum(
            1 for candidate in self._candidates
            if self.adapter.ready(candidate.stock_code)
            and (
                not pipeline_v2
                or self._active_pool_snapshot is None
                or candidate.stock_code in self._active_pool_snapshot.codes
            )
        )
        readiness_universe_count = (
            len(self._active_pool_snapshot.codes)
            if pipeline_v2 and self._active_pool_snapshot is not None
            else len(self._candidates)
        )
        if self.store is not None:
            store_health = self.store.health()
            store_path = self.store.path
        else:
            store_health = {"ok": not self.require_persistence, "mode": "legacy_no_store"}
            store_path = self.log_base
        readiness = build_readiness_report(
            store_health=store_health,
            store_path=store_path,
            lease_ok=lease_ok,
            state_ok=bool(
                self._state is not None
                and not self._state.recovery_error
                and not self._state.unknown_submits
                and oms_unknown_count == 0
            ),
            universe_count=readiness_universe_count,
            strategy_ready_count=strategy_ready_count,
            min_strategy_ready_coverage=self.config.min_strategy_ready_coverage,
            min_store_free_mb=self.config.min_store_free_mb,
        )
        if self.execution_mode == "INTERNAL_PAPER":
            # 本地撮合可以安全地只处理已经预热完成的标的；未达到 warmup
            # 的标的会在候选循环中跳过，并由 K 线重试逐步补齐，不能因此
            # 把整个内部模拟账户停成 dry-run。外部模拟盘仍保持全量门禁。
            #
            # D-WARMUP-01（2026-10-13 修正）：降级为软门禁本身是合理取舍（避免全停），
            # 但此前只把 hard 置 False，readiness["ready"] 仍为 True，页面会显示
            # 「就绪」而实际有大量标的未预热、在该状态下**不可能产生任何买卖信号**
            # （未就绪标的的信号在下方被强制为 hold/WARMUP 并 continue）。
            # 这里额外写出 partial 标记、覆盖数与未就绪数，让上层如实展示部分就绪。
            for check in readiness["checks"]:
                if check.get("name") == "strategy_warmup":
                    check["hard"] = False
                    check["scope"] = "ready_symbols_only"
                    check["partial"] = True
                    ready_n = int(check.get("ready") or check.get("ready_count") or 0)
                    total_n = int(check.get("total") or check.get("universe_count") or 0)
                    if total_n <= 0:
                        total_n = readiness_universe_count
                    if ready_n <= 0:
                        ready_n = strategy_ready_count
                    check["not_ready_count"] = max(total_n - ready_n, 0)
                    check["detail"] = (
                        f"部分就绪：{ready_n}/{total_n} 只已完成策略预热，"
                        f"{max(total_n - ready_n, 0)} 只未就绪（未就绪标的不会产生买卖信号，仅记 WARMUP）"
                    )
                    readiness["partial"] = True
                    readiness["strategy_ready_count"] = ready_n
                    readiness["universe_count"] = total_n
                    break
        readiness["lease"] = lease_detail
        # 日历可用性：真正的 fail-closed 在更早处（calendar.is_trading_day 返回 false 即 SLEEPING，
        # 异常则 BLOCKED），能走到这里说明本轮已判定为交易日。故本项是**如实展示**而非第二道硬门禁，
        # 用 hard=False 避免「同一条件既前置阻断又被记为就绪失败」这种双重记账。
        calendar_available = bool(getattr(self.calendar, "available", True))
        readiness["checks"].extend([
            # D-CAL-01（2026-10-13 修正）：此前该检查把 ok 硬编码为 True，即使日历是
            # weekday_fallback（不含节假日）也显示为「就绪」，页面无法看出节假日风险。
            {"name": "trading_calendar",
             "ok": calendar_available,
             "hard": False,
             "source": self.calendar.source,
             "note": "" if calendar_available
             else "交易日历不可用（weekday 回退，节假日会被当作交易日）"},
            {"name": "account_queries", "ok": True, "hard": True,
             "positions": len(positions), "orders": len(orders)},
            {"name": "oms_reconciled", "ok": oms_unknown_count == 0, "hard": True,
             "unknown_orders": oms_unknown_count,
             "active_orders": self._oms_active_count()},
        ])
        if pipeline_v2:
            rule_coverage = len(self._security_rules) / len(self._candidates) if self._candidates else 0.0
            readiness["checks"].extend([
                {"name": "l1_market_data", "ok": self._latest_l1_batch is not None,
                 "hard": True,
                 "coverage": round(self._latest_l1_batch.coverage, 6) if self._latest_l1_batch else 0.0},
                {"name": "security_rules", "ok": rule_coverage >= self.config.min_quote_coverage,
                 "hard": True, "coverage": round(rule_coverage, 6)},
                {"name": "l2_capacity", "ok": not self._active_pool_snapshot.capacity_exceeded,
                 "hard": True, "active": len(self._active_pool_snapshot.codes),
                 "mandatory": len(self._active_pool_snapshot.mandatory_codes)},
            ])
        readiness["ready"] = all(
            check["ok"] for check in readiness["checks"] if check.get("hard")
        )
        # D-WARMUP-01：partial 表示「硬门禁全过，但存在被降级为软门禁的检查项」。
        # ready 会被置为 False 以免页面把「部分就绪」当成「全量就绪」；
        # 是否允许继续运行由硬门禁决定，与 ready 的展示语义分离。
        if readiness.get("partial"):
            readiness["ready"] = False
            readiness["partial_note"] = (
                "部分就绪：硬门禁已通过，但策略预热覆盖未达 "
                f"{self.config.min_strategy_ready_coverage}，未就绪标的不会产生买卖信号"
            )
        tick.metrics["readiness"] = readiness
        events.emit("READINESS", cycle=self._cycle, detail=readiness, now=current)
        # D-WARMUP-01：阻断判定只看**硬门禁失败**，不看 ready 的展示语义。
        # ready=False 现在也涵盖「部分就绪（预热覆盖不足但已降级为软门禁）」，
        # 若直接据此阻断，会把降级的意义抹掉（等于把内部模拟盘全停成 dry-run）。
        if actual_live and self.require_persistence and _hard_failed(readiness):
            self._live_halted = True
            tick.live = False
            tick.errors.append("LIVE_READINESS_BLOCKED:持久化/策略预热/磁盘检查未通过")
            tick.status = "BLOCKED"
            return self._finish(tick)
        # 账户查询是轮初快照；同轮后续意图必须看到前序买单的资金占用和
        # 活动委托承诺。使用副本避免改写 tick.account 中的柜台原始快照。
        projected_balance = replace(balance)
        pool_pcts: list[float] = [
            float(quote.pct_chg)
            for quote in self._latest_l1_quotes.values()
            if pipeline_v2
            and getattr(quote, "validity", None) == QuoteValidity.VALID
            and getattr(quote, "pct_chg", None) is not None
        ]
        quotes: dict[str, object] = {}
        updates: dict[str, object] = {}
        l2_quality: dict[str, dict] = {}
        l2_request_ms: list[int] = []
        l2_quote_age_sec: list[float] = []

        # 监控轮询统一优先走东方财富公开批量快照：
        # - WATCHLIST/T1：批量覆盖整个观察池；
        # - FULL：批量覆盖当前 L2 活跃池。
        # 只有自定义行情客户端不提供 snapshot_many 时，才回退到旧的
        # 单标的 snapshot/snapshot_execution 路径。妙想查询不再作为常规轮询源。
        batch_quotes = None
        quote_results: dict[str, tuple[object | None, object | None]] = {}
        batch_started_monotonic = time.monotonic()
        if hasattr(self.quote_client, "snapshot_many"):
            # 已提供批量客户端时，失败也必须保持批量失败结果，不能落入
            # 旧的逐只 snapshot 路径（那条路径会调用妙想 /query）。
            batch_quotes = {}
            batch_candidates = self._candidates
            if pipeline_v2 and self._active_pool_snapshot is not None:
                active_codes = self._active_pool_snapshot.codes
                batch_candidates = [
                    candidate for candidate in self._candidates
                    if candidate.stock_code in active_codes
                ]
            try:
                batch_result = self.quote_client.snapshot_many(
                    batch_candidates,
                    run_id=run_id,
                    received_at=current,
                )
                batch_quotes = batch_result if isinstance(batch_result, dict) else {}
                quote_results.update(batch_quotes)
            except Exception as exc:
                tick.errors.append(f"PUBLIC_QUOTE_BATCH_FAILED:{type(exc).__name__}:{exc}")

        # 先完成本轮所有行情和 bar 聚合，再计算观察池中位数风控。
        for candidate in self._candidates:
            if candidate.stock_code in self._disabled_codes:
                continue
            if pipeline_v2 and (
                self._active_pool_snapshot is None
                or candidate.stock_code not in self._active_pool_snapshot.codes
            ):
                continue
            if batch_quotes is not None:
                quote, result = batch_quotes.get(candidate.stock_code, (None, None))
            elif pipeline_v2:
                request_at = self.now_fn()
                request_started = time.monotonic()
                try:
                    quote, result = self.market_data.snapshot_execution(
                        candidate.stock_code,
                        candidate.stock_name,
                        run_id=run_id,
                        received_at=request_at,
                    )
                except Exception as exc:  # 单标的供应商异常不得打断全池和 OMS 对账
                    quote, result = None, None
                    tick.errors.append(
                        f"L2_QUOTE_EXCEPTION:{candidate.stock_code}:"
                        f"{type(exc).__name__}:{exc}"
                    )
                l2_request_ms.append(
                    max(int((time.monotonic() - request_started) * 1000), 0)
                )
            else:
                quote, result = self.quote_client.snapshot(
                    candidate.stock_code,
                    candidate.stock_name,
                    run_id=run_id,
                    received_at=current,
                )
            quote_results[candidate.stock_code] = (quote, result)
            if quote is None:
                message = _result_message(result)
                tick.errors.append(f"quote {candidate.stock_code} 失败: {message}")
                if pipeline_v2:
                    l2_quality[candidate.stock_code] = self._l2_quality_engine.assess_quote(
                        candidate.stock_code, None, now=self.now_fn()
                    )
                events.emit("QUOTE_FAIL", code=candidate.stock_code, name=candidate.stock_name, cycle=self._cycle,
                            detail={"message": message}, now=current)
                if result is not None and result.stop_run():
                    self._live_halted = True
                    tick.errors.append("STOP_RUN: 行情错误触发停止性错误")
                    events.emit("ERROR", code=candidate.stock_code, name=candidate.stock_name, cycle=self._cycle,
                                detail={"scope": "STOP_RUN", "message": "行情错误触发停止性错误"}, now=current)
                    break
                continue
            quality_now = self.now_fn() if pipeline_v2 else current
            if pipeline_v2 and quote.market_time is not None:
                execution_age = (quality_now - quote.market_time).total_seconds()
                l2_quote_age_sec.append(max(execution_age, 0.0))
                if execution_age > self.config.execution_quote_stale_sec:
                    quote.validity = QuoteValidity.STALE
                    quote.error = (
                        f"EXECUTION_QUOTE_STALE:{execution_age:.1f}s>"
                        f"{self.config.execution_quote_stale_sec}s"
                    )
            quotes[candidate.stock_code] = quote
            if pipeline_v2:
                quality = self._l2_quality_engine.assess_quote(
                    candidate.stock_code, quote, now=quality_now
                )
                l2_quality[candidate.stock_code] = quality
                if quality["quality"] in {"INVALID", "STALE", "MISSING"}:
                    quote.validity = (
                        QuoteValidity.STALE if quality["quality"] == "STALE"
                        else QuoteValidity.INVALID
                    )
                    quote.error = quality["reason"]
            display_name = str(getattr(quote, "name", "") or candidate.stock_name)
            quote_event_state = (
                quote.validity.value,
                l2_quality.get(candidate.stock_code, {}).get("quality", quote.validity.value),
            )
            if self.config.emit_quote_events or self._quote_event_state.get(candidate.stock_code) != quote_event_state:
                events.emit("QUOTE", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail={
                    "stock_name": display_name,
                    "last": quote.last,
                    "pct_chg": quote.pct_chg,
                    "volume": quote.volume,
                    "validity": quote.validity.value,
                    "quality": quote_event_state[1],
                    "level": "L2" if pipeline_v2 else "LEGACY",
                    # D-QUOTE-01（2026-10-13 修正）：此前 QUOTE 事件不含数据时间与来源，
                    # 而 _apply_last_good_cache 会把上一轮快照重标为 VALID 并计为已覆盖，
                    # 于是最长 120 秒的源站空洞在事件流与页面上完全不可见。
                    # 这里显式输出源时间、接收时间、新鲜度与来源（含 "+cache" 标记）。
                    "source": getattr(quote, "source", "") or "",
                    "market_time": getattr(getattr(quote, "market_time", None), "isoformat", lambda: "")(),
                    "received_at": getattr(getattr(quote, "received_at", None), "isoformat", lambda: "")(),
                    "freshness_seconds": getattr(quote, "freshness_seconds", None),
                    "cache_filled": "+cache" in (getattr(quote, "source", "") or ""),
                }, now=current)
                self._quote_event_state[candidate.stock_code] = quote_event_state
            if quote.validity != QuoteValidity.VALID:
                tick.errors.append(
                    f"quote {candidate.stock_code} 不可交易: {quote.validity.value}"
                    + (f" ({quote.error})" if quote.error else "")
                )
                # STALE/INVALID/NOT_TRADABLE 不能进入 bar 或池中位数，
                # 否则上一笔/未来时间的价格会污染后续信号。
                continue
            if not pipeline_v2 and quote.pct_chg is not None:
                pool_pcts.append(quote.pct_chg)
            try:
                update = self.bar_store.update_snapshot(
                    candidate.stock_code,
                    quote,
                    # 以交易所行情时间分桶，避免网络延迟把上一桶的快照
                    # 错放进本地接收时刻对应的新桶。
                    quote.market_time or quote.received_at or current,
                )
            except ValueError as exc:
                tick.errors.append(f"bar {candidate.stock_code} 失败: {exc}")
                continue
            updates[candidate.stock_code] = update
            for closed_bar in update.new_closed:
                events.emit("BAR_CLOSED", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail={
                    "open": closed_bar.open, "high": closed_bar.high, "low": closed_bar.low,
                    "close": closed_bar.close, "volume": closed_bar.volume,
                    "start_ts": closed_bar.start_ts.isoformat(), "end_ts": closed_bar.end_ts.isoformat(),
                }, now=current)
            tick.new_closed_bars.extend(bar.to_dict() for bar in update.new_closed)

        if not pipeline_v2:
            processed_at = self.now_fn()
            try:
                self._persist_latest_quotes(
                    {code: quote for code, (quote, _) in quote_results.items() if quote is not None},
                    {},
                    batch_id=f"{run_id}-watch",
                    processed_at=processed_at,
                    candidates=self._candidates,
                    results=quote_results,
                )
                requested_count = len(self._candidates)
                returned_count = sum(
                    1 for quote, _ in quote_results.values() if quote is not None
                )
                covered_count = sum(
                    1 for quote, _ in quote_results.values()
                    if quote is not None and getattr(quote, "validity", None)
                    in {QuoteValidity.VALID, QuoteValidity.NOT_TRADABLE}
                )
                batch_meta = getattr(self.quote_client, "last_batch_meta", {}) or {}
                # D-QUOTE-01：明确披露「缓存兜底」条数。这些标的在源站本轮缺失、
                # 由上一轮快照重标为 VALID，会计入 covered_count/coverage，
                # 必须让使用者能看到有多少覆盖率其实来自旧值而非本轮源数据。
                cache_fill_count = int(batch_meta.get("cache_fill_count", 0) or 0)
                batch_summary = {
                    "batch_id": f"{run_id}-watch",
                    "provider": "eastmoney_ulist"
                    if batch_quotes is not None else "legacy_single_snapshot",
                    "trade_date": trade_date,
                    "started_at": current.isoformat(),
                    "received_at": processed_at.isoformat(),
                    "finished_at": processed_at.isoformat(),
                    "requested_count": requested_count,
                    "returned_count": returned_count,
                    "covered_count": covered_count,
                    "missing_count": max(requested_count - returned_count, 0),
                    "duplicate_count": int(batch_meta.get("duplicate_codes") and len(batch_meta["duplicate_codes"]) or 0),
                    "page_count": int(batch_meta.get("page_count", 0) or 0),
                    "coverage": round(covered_count / requested_count, 6)
                    if requested_count else 0.0,
                    "cache_fill_count": cache_fill_count,
                    "source_coverage": round(max(covered_count - cache_fill_count, 0) / requested_count, 6)
                    if requested_count else 0.0,
                    "elapsed_ms": max(int((time.monotonic() - batch_started_monotonic) * 1000), 0),
                    "status": "OK" if covered_count == requested_count else "DEGRADED",
                }
                quality_rows = []
                for candidate in self._candidates:
                    quote, result = quote_results.get(candidate.stock_code, (None, None))
                    quality_rows.append({
                        "code": candidate.stock_code,
                        "quality": getattr(
                            getattr(quote, "validity", None), "value", "MISSING"
                        ),
                        "reason": getattr(quote, "error", "") or (
                            _result_message(result) if quote is None else ""
                        ),
                        "last_market_time": getattr(getattr(quote, "market_time", None), "isoformat", lambda: "")(),
                        "last_received_at": getattr(getattr(quote, "received_at", None), "isoformat", lambda: "")(),
                        "last_price": getattr(quote, "last", None),
                        "last_volume": getattr(quote, "volume", None),
                        # D-QUOTE-01：把来源与缓存兜底标记落到行情质量行，
                        # serve._market_snapshot 回读给面板，使「缓存兜底」在表格中可见。
                        "source": getattr(quote, "source", "") or "",
                        "cache_filled": "+cache" in (getattr(quote, "source", "") or ""),
                    })
                if self.store is not None and callable(getattr(self.store, "save_market_data_batch", None)):
                    self.store.save_market_data_batch(batch_summary, quality_rows)
                events.emit("MARKET_DATA", cycle=self._cycle, detail={
                    "phase": "WATCHLIST_BATCH" if batch_quotes is not None else "WATCHLIST_POLL",
                    **batch_summary,
                }, now=current)
            except WatchStoreError as exc:
                tick.errors.append(f"WATCH_QUOTE_PERSIST_FAILED:{type(exc).__name__}:{exc}")

        if pipeline_v2:
            try:
                self._persist_latest_quotes(
                    quotes, l2_quality, batch_id=f"{run_id}-l2", processed_at=self.now_fn()
                )
                if self.store is not None and callable(getattr(self.store, "save_quote_quality", None)):
                    self.store.save_quote_quality("L2", trade_date, l2_quality.values())
            except WatchStoreError as exc:
                tick.errors.append(f"L2_QUOTE_PERSIST_FAILED:{type(exc).__name__}:{exc}")

        closed_bars = [bar for update in updates.values() for bar in update.new_closed]
        if self.store is not None:
            checkpoints = [
                checkpoint for code in updates
                for checkpoint in [self.bar_store.checkpoint(code)]
                if checkpoint is not None
            ]
            try:
                self.store.save_market_cycle(
                    trade_date=trade_date,
                    closed_bars=closed_bars,
                    forming_checkpoints=checkpoints,
                )
            except WatchStoreError as exc:
                tick.errors.append(f"MARKET_STATE_PERSIST_FAILED:{type(exc).__name__}:{exc}")
                self._live_halted = True
                tick.live = False
                if actual_live:
                    tick.status = "BLOCKED"
                events.emit("ERROR", cycle=self._cycle, detail={
                    "scope": "MARKET_STATE", "message": tick.errors[-1],
                }, now=current)

        if actual_live and closed_bars and hasattr(self.broker, "process_bars"):
            try:
                fills = self.broker.process_bars(closed_bars, now=current)
                tick.metrics["internal_fill_count"] = len(fills)
                for fill in fills:
                    events.emit("FILL", code=str(fill.get("code", "")), cycle=self._cycle,
                                detail=fill, now=current)
                if fills:
                    balance, positions, orders, account_error, _ = self._account(run_id)
                    if account_error:
                        raise WatchStoreError(account_error)
                    tick.account["after_match"] = self._account_snapshot(balance, positions, orders)
                    self._persist_account_snapshot(
                        run_id=run_id, phase="after_match", trade_date=trade_date,
                        balance=balance, positions=positions, orders=orders, captured_at=current,
                    )
                    events.emit("ACCOUNT", cycle=self._cycle, detail={
                        **self._account_event_detail(
                            balance, positions, orders,
                            phase="after_match", fill_count=len(fills),
                        ),
                    }, now=current)
                    position_map = {position.code: position for position in positions}
                    projected_balance = replace(balance)
            except (OSError, TypeError, ValueError, WatchStoreError) as exc:
                self._live_halted = True
                tick.live = False
                tick.errors.append(f"INTERNAL_MATCH_FAILED:{type(exc).__name__}:{exc}")
                if actual_live:
                    tick.status = "BLOCKED"

        covered_quote_count = sum(
            1 for quote in quotes.values()
            if getattr(quote, "validity", None) in {QuoteValidity.VALID, QuoteValidity.NOT_TRADABLE}
        )
        quote_coverage = (
            self._latest_l1_batch.coverage
            if pipeline_v2 and self._latest_l1_batch is not None
            else covered_quote_count / len(self._candidates) if self._candidates else 0.0
        )
        mandatory_codes = (
            self._active_pool_snapshot.mandatory_codes
            if pipeline_v2 and self._active_pool_snapshot is not None
            else {code for code, position in position_map.items() if position.qty > 0}
        )
        unavailable_position_quotes = sorted(
            code for code in mandatory_codes
            if code in candidate_codes
            and (
                code not in quotes
                or getattr(quotes[code], "validity", None)
                not in {QuoteValidity.VALID, QuoteValidity.NOT_TRADABLE}
            )
        )
        # 本地 INTERNAL_PAPER 不需要把整个观察池的覆盖率当成柜台发单前置条件；
        # 缺行情的标的会在上面的逐标的循环中被跳过，其他有效标的仍可本地撮合。
        # EXTERNAL_PAPER 仍保持全局覆盖率 fail-closed，避免把不完整行情送到妙想。
        quote_coverage_hard = self.execution_mode != "INTERNAL_PAPER"
        partial_quotes = quote_coverage < self.config.min_quote_coverage
        if partial_quotes:
            if tick.status == "OK": tick.status = "DEGRADED"
            tick.errors.append("PARTIAL_QUOTE_COVERAGE:覆盖不足，暂不作全观察池判断")
            readiness["partial"] = True
        # 同理：个别持仓缺行情不应永久掐断内部 Paper 的 live。
        # 缺行情持仓本身无法定价/下单（上方循环会跳过），其余标的应继续本地撮合。
        position_quotes_hard = self.execution_mode != "INTERNAL_PAPER"
        if actual_live and quote_coverage_hard and quote_coverage < self.config.min_quote_coverage:
            self._live_halted = True
            actual_live = False
            tick.live = False
            tick.status = "BLOCKED"
            tick.errors.append(
                f"QUOTE_COVERAGE_BLOCKED:{quote_coverage:.2%}<{self.config.min_quote_coverage:.2%}"
            )
            events.emit("ERROR", cycle=self._cycle, detail={
                "scope": "QUOTE_COVERAGE",
                "message": tick.errors[-1],
            }, now=current)
        if actual_live and unavailable_position_quotes:
            if position_quotes_hard:
                self._live_halted = True
                actual_live = False
                tick.live = False
                tick.status = "BLOCKED"
                tick.errors.append(
                    "POSITION_QUOTE_UNAVAILABLE:" + ",".join(unavailable_position_quotes)
                )
            else:
                # INTERNAL_PAPER：本轮降级提示，但保持 live，允许有行情的标的继续撮合。
                tick.errors.append(
                    "POSITION_QUOTE_DEGRADED:" + ",".join(unavailable_position_quotes)
                )
                if tick.status == "OK":
                    tick.status = "DEGRADED"
            events.emit("ERROR", cycle=self._cycle, detail={
                "scope": "POSITION_QUOTE",
                "message": tick.errors[-1],
                "hard": position_quotes_hard,
                "unavailable_codes": unavailable_position_quotes,
            }, now=current)
        market_checks = [
            {"name": "quote_coverage", "ok": quote_coverage >= self.config.min_quote_coverage,
             "hard": quote_coverage_hard, "coverage": round(quote_coverage, 6),
             "minimum": self.config.min_quote_coverage},
            {"name": "position_quotes", "ok": not unavailable_position_quotes,
             "hard": position_quotes_hard, "unavailable_codes": unavailable_position_quotes,
             "scope": "mandatory_l2" if pipeline_v2 else "positions"},
        ]
        readiness["checks"].extend(market_checks)
        readiness["ready"] = all(
            check["ok"] for check in readiness["checks"] if check.get("hard")
        )
        # D-WARMUP-01：此处会重算 ready，必须与上面保持一致地反映「部分就绪」，
        # 否则行情检查阶段会把预热覆盖不足重新抹成「就绪」。
        if readiness.get("partial"):
            readiness["ready"] = False
        readiness["phase"] = "market_checked"
        events.emit("READINESS", cycle=self._cycle, detail=readiness, now=current)

        pool_median_pct = median(pool_pcts) if pool_pcts and not partial_quotes else None
        cycle_submits = 0
        frozen_codes: set[str] = set()
        strategy_progress: list[dict] = []
        for candidate in self._candidates:
            update = updates.get(candidate.stock_code)
            quote = quotes.get(candidate.stock_code)
            if update is None or quote is None:
                continue
            display_name = str(getattr(quote, "name", "") or candidate.stock_name)
            for bar in update.new_closed:
                ready_before = self.adapter.ready(candidate.stock_code)
                position = position_map.get(candidate.stock_code)
                signal = self.adapter.on_closed_bar(
                    candidate.stock_code,
                    bar,
                    position.qty if position else 0,
                )
                signal.quality_status = "PARTIAL_COVERAGE" if partial_quotes else "PASS"
                signal.quality_detail = {
                    "coverage": round(quote_coverage, 6), "minimum_coverage": self.config.min_quote_coverage,
                    "universe_source": self._universe_source, "universe_count": len(self._candidates),
                    "quote_source": getattr(quote, "source", ""),
                    "market_time": (quote.market_time.replace(tzinfo=clock.CN_TZ).isoformat()
                                    if quote.market_time is not None and quote.market_time.tzinfo is None
                                    else quote.market_time.isoformat() if quote.market_time else None),
                    "received_at": (quote.received_at.replace(tzinfo=clock.CN_TZ).isoformat()
                                    if quote.received_at is not None and quote.received_at.tzinfo is None
                                    else quote.received_at.isoformat() if quote.received_at else None),
                    "freshness_seconds": getattr(quote, "freshness_seconds", None),
                    "validity": getattr(getattr(quote, "validity", None), "value", "UNKNOWN"),
                    "bar_source": bar.source,
                    "selection_lineage": candidate.selection_lineage,
                }
                decision_time = current.replace(tzinfo=clock.CN_TZ) if current.tzinfo is None else current
                if decision_time >= datetime.fromisoformat(signal.valid_until) and signal.action in {"buy", "sell"}:
                    signal.action = "hold"
                    signal.signal_status = "INVALIDATED"
                    signal.reason_codes.append("EXPIRED_BAR")
                strategy_progress.append({
                    "code": candidate.stock_code,
                    "strategy": self.adapter.strategy_name,
                    "parameter_hash": self.adapter.parameter_hash(),
                    "last_processed_bar_ts": bar.end_ts.isoformat(),
                    "warmup_ready": self.adapter.ready(candidate.stock_code),
                })
                if not ready_before:
                    signal.action = "hold"
                    signal.signal_status = "WARMUP"
                    signal.reason_codes = ["WARMUP", "BAR_CLOSED"]
                tick.signals.append(signal.to_dict())
                events.emit("SIGNAL", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail=signal.to_dict(), now=current)
                if not ready_before:
                    continue
                if (
                    actual_live
                    and self.execution_mode == "EXTERNAL_PAPER"
                    and getattr(quote, "source", "") == "eastmoney_batch"
                    and signal.action in {"buy", "sell"}
                ):
                    # 公开批量行情只负责轮询和本地信号。只有外部模拟盘
                    # live 且真正产生买卖信号时，才补查一次妙想执行级字段，
                    # 避免把常规行情轮询配额消耗在妙想 query 上。
                    execution_request_at = self.now_fn()
                    authoritative, auth_result = self.quote_client.snapshot(
                        candidate.stock_code,
                        candidate.stock_name,
                        run_id=run_id,
                        received_at=execution_request_at,
                    )
                    authoritative = self._enforce_execution_freshness(
                        authoritative, self.now_fn()
                    )
                    if authoritative is None or authoritative.validity != QuoteValidity.VALID:
                        quote.validity = QuoteValidity.INVALID
                        quote.error = "AUTHORITATIVE_QUOTE_UNAVAILABLE"
                        tick.errors.append(
                            f"quote {candidate.stock_code} 权威补充失败: {_result_message(auth_result)}"
                        )
                        if auth_result is not None and auth_result.stop_run():
                            self._live_halted = True
                        # 继续走风险闸，确保日志明确记录 QUOTE_NOT_VALID，
                        # 而不是静默跳过这次信号。
                    else:
                        quote = authoritative
                        quotes[candidate.stock_code] = quote
                        display_name = str(getattr(quote, "name", "") or display_name)
                        events.emit("QUOTE", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail={
                            "stock_name": display_name,
                            "last": quote.last,
                            "pct_chg": quote.pct_chg,
                            "volume": quote.volume,
                            "validity": quote.validity.value,
                            "authoritative": True,
                        }, now=current)
                security_rule = self._security_rules.get(candidate.stock_code)
                quote = apply_rule_price_limits(quote, security_rule)
                intent = build_watch_intent(
                    candidate,
                    signal,
                    quote,
                    projected_balance,
                    position,
                    self.config,
                    now=current,
                    security_rule=security_rule,
                )
                if candidate.stock_code in frozen_codes:
                    intent.intent_type = IntentType.NOOP
                    intent.risk_reject_reason = "FROZEN_PREVIOUS_UNKNOWN"
                    intent.reason_codes.append("FROZEN_PREVIOUS_UNKNOWN")
                if intent.intent_type in {IntentType.BUY_LIMIT, IntentType.SELL_LIMIT}:
                    events.emit("SIZING", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail=sizing_detail(
                        projected_balance,
                        intent.limit_price if intent.side == "buy" else quote.last,
                        self.config,
                        intent.side,
                        available_qty=position.avail_qty if position else 0,
                    ), now=current)
                gate_trace: list = []
                # check_watch_intent 拒绝时会原地改成 NOOP，先记录原始意图类型
                original_intent_type = intent.intent_type.value
                checked = check_watch_intent(
                    intent,
                    quote,
                    projected_balance,
                    position_map,
                    orders,
                    self.config,
                    trades_this_day=self._state.day_trade_count if self._state else 0,
                    trades_this_cycle=cycle_submits,
                    cooldown_ok=self._cooldown.can_trade(candidate.stock_code, intent.side, current),
                    universe_codes={item.stock_code for item in self._candidates},
                    pool_median_pct_chg=pool_median_pct,
                    confirmed=True,
                    # 内部 Paper 不触达柜台，可以基于公开行情完成本地
                    # 撮合；只有外部妙想模拟盘要求权威涨跌停字段。
                    strict_limit_data=bool(
                        actual_live and self.execution_mode == "EXTERNAL_PAPER"
                    ),
                    security_rule=security_rule,
                    require_security_rule=bool(
                        actual_live
                        and self.execution_mode == "EXTERNAL_PAPER"
                        and pipeline_v2
                    ),
                    gate_trace=gate_trace,
                )
                events.emit("RISK_GATE", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail={
                    "intent": original_intent_type,
                    "qty": intent.qty,
                    "limit_price": intent.limit_price,
                    "result": "PASS" if checked.risk_status.value == "PASS" and checked.intent_type == intent.intent_type else "REJECT",
                    "reject_reason": checked.risk_reject_reason or "",
                    "gates": gate_trace,
                }, now=current)
                tick.intents.append(checked.to_dict())
                if checked.intent_type not in {IntentType.BUY_LIMIT, IntentType.SELL_LIMIT} or checked.risk_status.value != "PASS":
                    continue
                if not actual_live:
                    checked.reason_codes.append("DRY_RUN_NO_SUBMIT")
                    tick.intents[-1] = checked.to_dict()
                    events.emit("SUBMIT", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail={
                        "status": "DRY_RUN", "side": checked.side, "qty": checked.qty, "price": checked.limit_price,
                    }, now=current)
                    cycle_submits += 1
                    if checked.intent_type == IntentType.BUY_LIMIT:
                        self._reserve_buy_commitment(
                            projected_balance,
                            orders,
                            checked,
                            f"dry-run-{run_id}-{cycle_submits}",
                        )
                    continue
                if self._live_halted:
                    # 本轮中途触发 STOP_RUN（行情失败或上一笔提交被拒）后，
                    # 剩余标的不得继续真实提交。
                    checked.reason_codes.append("LIVE_HALTED_NO_SUBMIT")
                    tick.intents[-1] = checked.to_dict()
                    continue
                lease_ok, lease_detail = self._ensure_live_lease(renew_only=True)
                if not lease_ok:
                    self._live_halted = True
                    tick.live = False
                    tick.status = "BLOCKED"
                    checked.reason_codes.append("LIVE_LEASE_LOST")
                    tick.intents[-1] = checked.to_dict()
                    tick.errors.append("LIVE_LEASE_LOST:提交前续租失败，已禁止后续发单")
                    events.emit("ERROR", code=candidate.stock_code, name=display_name,
                                cycle=self._cycle, detail={"scope": "LIVE_LEASE", "lease": lease_detail}, now=current)
                    continue
                local_order: LocalOrder | None = None
                if self.oms is not None:
                    try:
                        local_order, created = self.oms.create(
                            run_id=run_id,
                            strategy=self.adapter.strategy_name,
                            signal_bar_ts=signal.bar_ts,
                            code=checked.stock_code,
                            side=checked.side,
                            qty=checked.qty,
                            limit_price=float(checked.limit_price),
                            ttl_sec=(
                                self.config.buy_order_ttl_sec
                                if checked.side == "buy" else self.config.sell_order_ttl_sec
                            ),
                            metadata={
                                "reason_codes": checked.reason_codes,
                                "quote_ts": checked.quote_ts,
                                "execution_mode": self.execution_mode,
                                "account_scope": f"{self.execution_mode}:{self.config.paper_session_id}",
                                "session_id": self.config.paper_session_id,
                                "strategy": self.adapter.strategy_name,
                                "signal_bar_ts": signal.bar_ts,
                                "market_data_level": "L2" if pipeline_v2 else "LEGACY",
                                "quote_source": getattr(quote, "source", ""),
                                "security_rule": (
                                    security_rule.to_dict()
                                    if security_rule is not None else None
                                ),
                            },
                            now=current,
                        )
                        if not created and local_order.status != OrderStatus.CREATED.value:
                            checked.reason_codes.append("OMS_IDEMPOTENT_DUPLICATE")
                            tick.intents[-1] = checked.to_dict()
                            tick.errors.append(
                                f"OMS_DUPLICATE_BLOCKED:{local_order.client_order_id}:{local_order.status}"
                            )
                            events.emit("ORDER", code=checked.stock_code, name=display_name,
                                        cycle=self._cycle, detail={
                                            "phase": "duplicate", "client_order_id": local_order.client_order_id,
                                            "status": local_order.status,
                                        }, now=current)
                            continue
                        local_order = self.oms.transition(
                            local_order.client_order_id, OrderStatus.SUBMITTING
                        )
                        events.emit("ORDER", code=checked.stock_code, name=display_name,
                                    cycle=self._cycle, detail={
                                        "phase": "submitting",
                                        "client_order_id": local_order.client_order_id,
                                        "idempotency_key": local_order.idempotency_key,
                                        "status": local_order.status,
                                    }, now=current)
                    except (TypeError, ValueError, WatchStoreError) as exc:
                        self._live_halted = True
                        tick.live = False
                        tick.status = "BLOCKED"
                        tick.errors.append(f"OMS_CREATE_FAILED:{type(exc).__name__}:{exc}")
                        continue
                record = self._submit(checked, run_id, local_order)
                if self.oms is not None and local_order is not None:
                    try:
                        if record.status == "SUBMITTED":
                            updated_order = self.oms.transition(
                                local_order.client_order_id,
                                OrderStatus.SUBMITTED,
                                broker_order_id=record.order_id,
                            )
                        elif record.status == "UNKNOWN_SUBMIT":
                            updated_order = self.oms.transition(
                                local_order.client_order_id,
                                OrderStatus.UNKNOWN,
                                broker_order_id=record.order_id,
                                last_error_code=record.error_code,
                                last_error_message=record.error_message,
                            )
                        else:
                            updated_order = self.oms.transition(
                                local_order.client_order_id,
                                OrderStatus.REJECTED,
                                broker_order_id=record.order_id,
                                last_error_code=record.error_code,
                                last_error_message=record.error_message,
                            )
                        events.emit("ORDER", code=checked.stock_code, name=display_name,
                                    cycle=self._cycle, detail={
                                        "phase": "submit_result",
                                        "client_order_id": updated_order.client_order_id,
                                        "broker_order_id": updated_order.broker_order_id,
                                        "status": updated_order.status,
                                    }, now=current)
                    except WatchStoreError as exc:
                        # 外部请求已经发生但 OMS 结果落库失败，无法证明订单状态。
                        self._live_halted = True
                        tick.live = False
                        tick.status = "BLOCKED"
                        tick.errors.append(f"OMS_RESULT_PERSIST_FAILED:{type(exc).__name__}:{exc}")
                tick.submits.append(asdict(record))
                events.emit("SUBMIT", code=candidate.stock_code, name=display_name, cycle=self._cycle, detail={
                    "status": record.status, "side": checked.side, "qty": checked.qty,
                    "price": checked.limit_price, "order_id": record.order_id,
                    "error_code": record.error_code, "error_message": record.error_message,
                }, now=current)
                cycle_submits += 1
                accepted_or_unknown = record.status in {"SUBMITTED", "UNKNOWN_SUBMIT"}
                if accepted_or_unknown:
                    if self._state is not None:
                        self._state.day_trade_count += 1
                    self._cooldown.record(candidate.stock_code, checked.side, current)
                if record.order_id and self._state is not None:
                    if record.order_id not in self._state.open_order_ids:
                        self._state.open_order_ids.append(record.order_id)
                if record.status == "UNKNOWN_SUBMIT" and self._state is not None:
                    self._live_halted = True
                    self._unknown_halt = True
                    self._state.unknown_submits.append({
                        "stock_code": record.stock_code,
                        "side": record.side,
                        "qty": record.qty,
                        "price": record.price,
                        "run_id": run_id,
                        "submitted_at": current.isoformat(),
                        "error_code": record.error_code,
                    })
                if self._state is not None:
                    try:
                        self._persist_state(trade_date)
                    except (OSError, WatchStoreError) as exc:
                        # 请求已经可能被柜台受理，但本地安全状态无法落盘；
                        # 本轮必须停止后续提交，仍继续执行末尾账户复查。
                        self._live_halted = True
                        tick.live = False
                        tick.status = "BLOCKED"
                        tick.errors.append(f"STATE_PERSIST_FAILED:{type(exc).__name__}")
                if record.status != "SUBMITTED":
                    frozen_codes.add(candidate.stock_code)
                    if getattr(record, "error_code", "") in {"113", "114", "115", "116", "404"}:
                        self._live_halted = True
                        tick.errors.append(f"STOP_RUN: trade {record.error_code}，后续切换 dry-run")
                    continue
                # 本轮后续判断使用本地活动委托和投影可用资金，避免不同
                # 股票的连续买单穿透组合上限。
                if checked.intent_type == IntentType.BUY_LIMIT:
                    self._reserve_buy_commitment(projected_balance, orders, checked, record.order_id)
                else:
                    orders.append(MxOrder(
                        order_id=record.order_id,
                        code=checked.stock_code,
                        side=checked.side,
                        price=checked.limit_price,
                        qty=checked.qty,
                        filled_qty=0,
                        status="2",
                    ))

        if self.store is not None and strategy_progress:
            try:
                self.store.save_strategy_progress(strategy_progress)
            except WatchStoreError as exc:
                self._live_halted = True
                tick.live = False
                tick.errors.append(f"STRATEGY_STATE_PERSIST_FAILED:{type(exc).__name__}:{exc}")
                if actual_live:
                    tick.status = "BLOCKED"

        if tick.submits:
            balance_after, positions_after, orders_after, post_error, post_stop = self._account(run_id)
            if post_error:
                tick.errors.append("POST_QUERY_FAILED:" + post_error)
                # 已经发过单而对账失败时，无论是网络、解析还是业务错误，
                # 都无法确认账户状态；后续必须停实盘，等待人工/柜台核对。
                self._live_halted = True
                tick.status = "BLOCKED"
            else:
                tick.account["after"] = self._account_snapshot(balance_after, positions_after, orders_after)
                self._sync_oms(orders_after, self.now_fn(), tick)
                for message in self._reconcile_unknown_submits(orders_after, trade_date):
                    tick.errors.append(message)
                    events.emit("ACCOUNT", cycle=self._cycle, detail={
                        "phase": "post_submit_reconcile", "message": message,
                    }, now=self.now_fn())
                try:
                    self._persist_account_snapshot(
                        run_id=run_id, phase="after_submit", trade_date=trade_date,
                        balance=balance_after, positions=positions_after, orders=orders_after,
                        captured_at=self.now_fn(),
                    )
                except WatchStoreError as exc:
                    self._live_halted = True
                    tick.live = False
                    tick.status = "BLOCKED"
                    tick.errors.append(f"ACCOUNT_SNAPSHOT_PERSIST_FAILED:{type(exc).__name__}:{exc}")
                events.emit("ACCOUNT", cycle=self._cycle, detail={
                    **self._account_event_detail(
                        balance_after, positions_after, orders_after, phase="after",
                    ),
                }, now=self.now_fn())
        if self._live_halted:
            tick.errors.append("LIVE_DISABLED_AFTER_STOP_RUN")
            tick.live = False
        # D-QUOTE-01：把「缓存兜底」的规模计入 tick 指标，使覆盖率可被拆解为
        # 「来自本轮源数据」与「来自上一轮快照兜底」两部分，而不是一个混合数字。
        cached_quote_count = sum(
            1 for quote in quotes.values()
            if "+cache" in (getattr(quote, "source", "") or "")
        )
        tick.metrics.update({
            "universe_source": self._universe_source,
            "universe_count": len(self._candidates),
            "quote_count": len(quotes),
            "valid_quote_count": sum(
                1 for quote in quotes.values() if getattr(quote, "validity", None) == QuoteValidity.VALID
            ),
            "covered_quote_count": covered_quote_count,
            "quote_coverage": round(quote_coverage, 6),
            "decision_status": "PARTIAL_COVERAGE" if partial_quotes else "OBSERVATION_ONLY",
            "market_wide_coverage": round(quote_coverage, 6) if self._universe_source == "full_market"
                and not any(e.startswith("FULL_UNIVERSE_") for e in self._prepare_errors) else None,
            "cached_quote_count": cached_quote_count,
            "source_quote_coverage": round(
                max(covered_quote_count - cached_quote_count, 0) / len(self._candidates), 6
            ) if self._candidates else 0.0,
            "strategy_ready_count": sum(
                1 for candidate in self._candidates if self.adapter.ready(candidate.stock_code)
            ),
            "buy_signal_count": sum(1 for signal in tick.signals if signal.get("action") == "buy"),
            "sell_signal_count": sum(1 for signal in tick.signals if signal.get("action") == "sell"),
        })
        if pipeline_v2:
            universe_material = "\n".join(
                f"{item.stock_code}|{item.exchange_type}|{item.stock_name}"
                for item in sorted(self._candidates, key=lambda candidate: candidate.stock_code)
            )
            universe_hash = sha256(universe_material.encode("utf-8")).hexdigest()
            tick.universe = [{
                "universe_hash": universe_hash,
                "count": len(self._candidates),
                "source": self._universe_source,
            }]
            tick.metrics.update({
                "market_pipeline": "L1_L2_V2",
                "l1_scanned": l1_scanned,
                "l1_batch": self._latest_l1_batch.to_summary() if self._latest_l1_batch else {},
                "l2_active_count": len(self._active_pool_snapshot.codes) if self._active_pool_snapshot else 0,
                "l2_mandatory_count": len(self._active_pool_snapshot.mandatory_codes) if self._active_pool_snapshot else 0,
                "l2_quote_count": len(quotes),
                "l2_quality": {
                    status: sum(1 for row in l2_quality.values() if row.get("quality") == status)
                    for status in ("VALID", "WARN", "STALE", "INVALID", "MISSING", "NOT_TRADABLE")
                },
                "l2_request_latency_ms": self._percentiles(l2_request_ms),
                "l2_quote_age_sec": self._percentiles(l2_quote_age_sec),
                "security_rule_count": len(self._security_rules),
                "provider_health": self.market_data.health().to_dict(),
            })
        if self.oms is not None and self.store is not None:
            try:
                local_orders = self.store.list_local_orders(
                    channel=self.execution_mode, session_id=self.config.paper_session_id
                )
                tick.metrics.update({
                    "local_order_count": len(local_orders),
                    "active_order_count": sum(
                        1 for order in local_orders if order.get("status") in {
                            OrderStatus.CREATED.value, OrderStatus.SUBMITTING.value,
                            OrderStatus.SUBMITTED.value, OrderStatus.PARTIAL.value,
                            OrderStatus.CANCEL_PENDING.value, OrderStatus.UNKNOWN.value,
                        }
                    ),
                    "unknown_order_count": sum(
                        1 for order in local_orders if order.get("status") == OrderStatus.UNKNOWN.value
                    ),
                    "fill_count": len(self.store.list_fills(session_id=self.config.paper_session_id)),
                })
            except WatchStoreError as exc:
                self._live_halted = True
                tick.live = False
                tick.errors.append(f"OMS_METRIC_FAILED:{type(exc).__name__}:{exc}")
                if actual_live:
                    tick.status = "BLOCKED"
        if tick.errors and tick.status == "OK":
            tick.status = "DEGRADED"
        return self._finish(tick)

    def run(
        self,
        signal_date: str,
        *,
        live: bool = False,
        confirm: bool = False,
        candidates_override=None,
        max_cycles: int | None = None,
    ) -> list[WatchTick]:
        """长循环；max_cycles 仅用于测试和人工短跑验证。"""
        try:
            self.prepare(signal_date or self.now_fn().strftime("%Y-%m-%d"), candidates_override)
        except (OSError, TypeError, ValueError):
            # 首轮 prepare 失败留给 run_once 记 BLOCKED tick，不让长循环裸栈退出。
            pass
        ticks: list[WatchTick] = []
        cycle = 0
        try:
            while max_cycles is None or cycle < max_cycles:
                tick = self.run_once(
                    signal_date,
                    live=live,
                    confirm=confirm,
                    candidates_override=candidates_override,
                )
                # 长时间运行时不把所有 tick 永久堆在内存中；短跑模式仍
                # 返回完整结果供 CLI/调用方使用。
                if max_cycles is not None:
                    ticks.append(tick)
                cycle += 1
                if max_cycles is not None and cycle >= max_cycles:
                    break
                self.sleep_fn(
                    self.config.l2_poll_interval_sec
                    if self._pipeline_v2_enabled()
                    else self.config.query_interval_sec
                )
        except KeyboardInterrupt:
            # Ctrl+C：不自动撤单，保留已落盘 tick 后优雅退出。
            pass
        return ticks
