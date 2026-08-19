"""盘中 watch 调度器：行情 → 已收盘 bar → 信号 → 风控 → dry-run/模拟盘。"""
from __future__ import annotations

import secrets
import time
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from statistics import median

from ..broker_client import ACTIVE_ORDER_STATUSES, order_remaining_qty
from ..config import PaperConfig
from ..decide import in_trading_window
from ..models import Candidate, IntentType, MxOrder, Position, SubmitRecord
from ..risk import check_watch_intent
from ..trading_calendar import TradingCalendarService, load_calendar_from_file
from .artifacts import append_daily_summary, write_tick
from .bar_store import BarStore
from .cooldown import CooldownBook
from .decide_watch import build_watch_intent
from .events import EventLog
from .size import sizing_detail
from .models import WatchTick
from .signal_adapter import SignalAdapter
from .state import WatchState
from .universe import load_watch_universe


def _run_id(now: datetime) -> str:
    return now.strftime("%Y%m%dT%H%M%S") + "-" + secrets.token_hex(4)


def _result_message(result) -> str:
    if result is None:
        return "无响应结果"
    return f"code={getattr(result, 'code', '?')} kind={getattr(getattr(result, 'kind', None), 'value', '?')} msg={getattr(result, 'message', '')}"


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
    ):
        self.config = config
        self.quote_client = quote_client
        self.broker = broker
        self.kline_client = kline_client
        self.log_base = Path(log_base)
        self.now_fn = now_fn or datetime.now
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
        self._prepared_date = ""
        self._candidates: list[Candidate] = []
        self._universe_source = ""
        self._prepare_errors: list[str] = []
        self._prepare_errors_pending = False
        self._disabled_codes: dict[str, str] = {}
        self._state: WatchState | None = None
        self._cooldown = CooldownBook(config.cooldown_min)
        self._live_halted = False
        self._events: EventLog | None = None
        self._events_date = ""
        self._cycle = 0

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

    def _event_log(self, trade_date: str) -> EventLog:
        # 按交易日懒加载事件流水；跨日自动轮转新文件，seq 续接。
        if self._events is None or self._events_date != trade_date or self._events._handle.closed:
            if self._events is not None:
                self._events.close()
            self._events = EventLog(self.log_base, trade_date, now_fn=self.now_fn)
            self._events_date = trade_date
        return self._events

    def prepare(self, signal_date: str, candidates_override=None, *, now: datetime | None = None) -> list[str]:
        """加载观察池、恢复状态并预热分钟 K 线。失败的股票当日禁用，不混用日线 fallback。"""
        current = now or self.now_fn()
        trade_date = current.strftime("%Y-%m-%d")
        candidates, source, errors = load_watch_universe(self.config, signal_date, candidates_override)
        self._candidates = candidates
        self._universe_source = source
        self._prepare_errors = list(errors)
        self._disabled_codes = {}
        self._state, self._cooldown = WatchState.load(
            self._state_path(trade_date), trade_date, self.config.cooldown_min
        )

        required = self.adapter.required_bars()
        for candidate in candidates:
            code = candidate.stock_code
            try:
                if code in self._seed_bars:
                    bars = list(self._seed_bars[code])
                elif self.kline_client is not None:
                    bars, result = self.kline_client.fetch_history(
                        code,
                        candidate.stock_name,
                        self.config.bar_period_min,
                        max(required + 10, 80),
                        run_id=f"watch-prep-{trade_date}",
                        as_of=current,
                        exchange=candidate.exchange_type,
                    )
                    if bars is None:
                        raise ValueError(_result_message(result))
                else:
                    raise ValueError("未提供 KlineClient 或 seed_bars")
                self.bar_store.seed(code, bars)
                self.adapter.seed(code, self.bar_store.history(code))
                if not self.adapter.ready(code):
                    self._prepare_errors.append(f"{code}:WARMUP不足({len(bars)}/{required})")
            except (OSError, TypeError, ValueError) as exc:
                self._disabled_codes[code] = str(exc)
                self._prepare_errors.append(f"{code}:KLINE_DISABLED:{exc}")

        self._prepared_date = trade_date
        self._prepare_errors_pending = True
        events = self._event_log(trade_date)
        for code, reason in self._disabled_codes.items():
            events.emit("ERROR", code=code, detail={"scope": "KLINE_DISABLED", "message": reason}, now=current)
        if errors:
            events.emit("ERROR", detail={"scope": "UNIVERSE", "messages": errors}, now=current)
        return list(self._prepare_errors)

    def _account(self, run_id: str):
        balance, b_result = self.broker.balance(run_id)
        if balance is None or (b_result is not None and not b_result.ok):
            return None, None, None, f"balance 查询失败: {_result_message(b_result)}", bool(b_result and b_result.stop_run())
        positions, p_result = self.broker.positions(run_id)
        if positions is None or (p_result is not None and not p_result.ok):
            return None, None, None, f"positions 查询失败: {_result_message(p_result)}", bool(p_result and p_result.stop_run())
        orders, o_result = self.broker.orders(run_id)
        if orders is None or (o_result is not None and not o_result.ok):
            return None, None, None, f"orders 查询失败: {_result_message(o_result)}", bool(o_result and o_result.stop_run())
        return balance, positions, orders, "", False

    @staticmethod
    def _account_snapshot(balance, positions, orders) -> dict:
        return {
            "total_assets": balance.total_assets,
            "avail_balance": balance.avail_balance,
            "market_value": balance.market_value,
            "positions": [
                {
                    "code": position.code,
                    "qty": position.qty,
                    "avail_qty": position.avail_qty,
                    "market_value": position.market_value,
                }
                for position in positions
            ],
            "orders": [
                {
                    "order_id": order.order_id,
                    "code": order.code,
                    "side": order.side,
                    "qty": order.qty,
                    "filled_qty": order.filled_qty,
                    "status": order.status,
                }
                for order in orders
            ],
        }

    @staticmethod
    def _order_id(result) -> str:
        data = getattr(result, "data", None)
        if isinstance(data, dict):
            for key in ("orderId", "order_id", "id"):
                if data.get(key):
                    return str(data[key])
        return ""

    def _submit(self, intent, run_id: str) -> SubmitRecord:
        try:
            result = self.broker.trade(
                intent.stock_code,
                intent.side,
                intent.qty,
                use_market_price=False,
                price=intent.limit_price,
                run_id=run_id,
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

    def _finish(self, tick: WatchTick) -> WatchTick:
        tick.finished_at = self.now_fn().isoformat()
        write_tick(self.log_base, tick)
        append_daily_summary(self.log_base, tick)
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
        events.emit("CYCLE", cycle=self._cycle, detail={"phase": "start", "run_id": run_id, "live_requested": bool(live)}, now=current)
        actual_live = bool(live and confirm and self.config.watch_live and not self._live_halted)
        tick = WatchTick(
            run_id=run_id,
            trade_date=trade_date,
            started_at=current.isoformat(),
            finished_at=current.isoformat(),
            live=actual_live,
        )

        if live and (not confirm or not self.config.watch_live):
            tick.errors.append("LIVE_CONFIRM_REQUIRED: --live、--confirm 与 PAPER_WATCH_LIVE=1 必须同时满足")
            tick.status = "BLOCKED"
            return self._finish(tick)
        if live and not self._calendar_explicit and not self.config.trading_calendar_file:
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
        tick.universe = [asdict(candidate) for candidate in self._candidates]
        if self._prepare_errors_pending:
            tick.errors.extend(self._prepare_errors)
            self._prepare_errors_pending = False
        if live and "T1_UNAVAILABLE" in self._prepare_errors:
            tick.errors.append("DATA_SOURCE_BLOCKED:T1 观察池不可用，live 不允许空名单继续")
            tick.status = "BLOCKED"
            return self._finish(tick)
        if not self._candidates:
            tick.status = "NO_UNIVERSE"
            return self._finish(tick)
        if not in_trading_window(current):
            tick.status = "SLEEPING"
            return self._finish(tick)
        if not self.calendar.is_trading_day(current):
            tick.errors.append(f"NOT_TRADING_DAY:{trade_date}")
            tick.status = "SLEEPING"
            return self._finish(tick)
        if self._live_halted:
            tick.errors.append("LIVE_DISABLED_AFTER_STOP_RUN: 后续仅 dry-run，不再提交")
            actual_live = False
            tick.live = False

        balance, positions, orders, account_error, account_stop = self._account(run_id)
        if account_error:
            tick.errors.append(account_error)
            if account_stop:
                self._live_halted = True
                tick.errors.append("STOP_RUN: 账户查询返回停止性错误，后续切换 dry-run")
            tick.status = "BLOCKED"
            return self._finish(tick)
        tick.account = self._account_snapshot(balance, positions, orders)
        events.emit("ACCOUNT", cycle=self._cycle, detail={
            "phase": "before",
            "total_assets": balance.total_assets,
            "avail_balance": balance.avail_balance,
            "positions": len(positions),
            "open_orders": len(orders),
        }, now=current)
        position_map = {position.code: position for position in positions}
        pool_pcts: list[float] = []
        quotes: dict[str, object] = {}
        updates: dict[str, object] = {}

        # 先完成本轮所有行情和 bar 聚合，再计算观察池中位数风控。
        for candidate in self._candidates:
            if candidate.stock_code in self._disabled_codes:
                continue
            quote, result = self.quote_client.snapshot(
                candidate.stock_code,
                candidate.stock_name,
                run_id=run_id,
                received_at=current,
            )
            if quote is None:
                message = _result_message(result)
                tick.errors.append(f"quote {candidate.stock_code} 失败: {message}")
                events.emit("QUOTE_FAIL", code=candidate.stock_code, cycle=self._cycle,
                            detail={"message": message}, now=current)
                if result is not None and result.stop_run():
                    self._live_halted = True
                    tick.errors.append("STOP_RUN: 行情错误触发停止性错误")
                    events.emit("ERROR", code=candidate.stock_code, cycle=self._cycle,
                                detail={"scope": "STOP_RUN", "message": "行情错误触发停止性错误"}, now=current)
                    break
                continue
            quotes[candidate.stock_code] = quote
            events.emit("QUOTE", code=candidate.stock_code, cycle=self._cycle, detail={
                "last": quote.last,
                "pct_chg": quote.pct_chg,
                "volume": quote.volume,
                "validity": quote.validity.value,
            }, now=current)
            if quote.pct_chg is not None:
                pool_pcts.append(quote.pct_chg)
            try:
                update = self.bar_store.update_snapshot(candidate.stock_code, quote, current)
            except ValueError as exc:
                tick.errors.append(f"bar {candidate.stock_code} 失败: {exc}")
                continue
            updates[candidate.stock_code] = update
            for closed_bar in update.new_closed:
                events.emit("BAR_CLOSED", code=candidate.stock_code, cycle=self._cycle, detail={
                    "open": closed_bar.open, "high": closed_bar.high, "low": closed_bar.low,
                    "close": closed_bar.close, "volume": closed_bar.volume,
                    "start_ts": closed_bar.start_ts.isoformat(), "end_ts": closed_bar.end_ts.isoformat(),
                }, now=current)
            tick.new_closed_bars.extend(bar.to_dict() for bar in update.new_closed)

        pool_median_pct = median(pool_pcts) if pool_pcts else None
        cycle_submits = 0
        frozen_codes: set[str] = set()
        for candidate in self._candidates:
            update = updates.get(candidate.stock_code)
            quote = quotes.get(candidate.stock_code)
            if update is None or quote is None:
                continue
            for bar in update.new_closed:
                if not self.adapter.ready(candidate.stock_code):
                    tick.signals.append({
                        "code": candidate.stock_code,
                        "action": "hold",
                        "strategy": self.config.watch_strategy,
                        "bar_ts": bar.end_ts.isoformat(),
                        "reason_codes": ["WARMUP"],
                    })
                    continue
                position = position_map.get(candidate.stock_code)
                signal = self.adapter.on_closed_bar(
                    candidate.stock_code,
                    bar,
                    position.qty if position else 0,
                )
                tick.signals.append(signal.to_dict())
                events.emit("SIGNAL", code=candidate.stock_code, cycle=self._cycle, detail=signal.to_dict(), now=current)
                intent = build_watch_intent(
                    candidate,
                    signal,
                    quote,
                    balance,
                    position,
                    self.config,
                    now=current,
                )
                if candidate.stock_code in frozen_codes:
                    intent.intent_type = IntentType.NOOP
                    intent.risk_reject_reason = "FROZEN_PREVIOUS_UNKNOWN"
                    intent.reason_codes.append("FROZEN_PREVIOUS_UNKNOWN")
                if intent.intent_type in {IntentType.BUY_LIMIT, IntentType.SELL_LIMIT}:
                    events.emit("SIZING", code=candidate.stock_code, cycle=self._cycle, detail=sizing_detail(
                        balance, quote.last, self.config, intent.side,
                        available_qty=position.avail_qty if position else 0,
                    ), now=current)
                gate_trace: list = []
                checked = check_watch_intent(
                    intent,
                    quote,
                    balance,
                    position_map,
                    orders,
                    self.config,
                    trades_this_day=self._state.day_trade_count if self._state else 0,
                    trades_this_cycle=cycle_submits,
                    cooldown_ok=self._cooldown.can_trade(candidate.stock_code, intent.side, current),
                    universe_codes={item.stock_code for item in self._candidates},
                    pool_median_pct_chg=pool_median_pct,
                    confirmed=True,
                    strict_limit_data=actual_live,
                    gate_trace=gate_trace,
                )
                events.emit("RISK_GATE", code=candidate.stock_code, cycle=self._cycle, detail={
                    "intent": intent.intent_type.value,
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
                    events.emit("SUBMIT", code=candidate.stock_code, cycle=self._cycle, detail={
                        "status": "DRY_RUN", "side": checked.side, "qty": checked.qty, "price": checked.limit_price,
                    }, now=current)
                    continue
                if self._live_halted:
                    # 本轮中途触发 STOP_RUN（行情失败或上一笔提交被拒）后，
                    # 剩余标的不得继续真实提交。
                    checked.reason_codes.append("LIVE_HALTED_NO_SUBMIT")
                    tick.intents[-1] = checked.to_dict()
                    continue
                record = self._submit(checked, run_id)
                tick.submits.append(asdict(record))
                events.emit("SUBMIT", code=candidate.stock_code, cycle=self._cycle, detail={
                    "status": record.status, "side": checked.side, "qty": checked.qty,
                    "price": checked.limit_price, "order_id": record.order_id,
                    "error_code": record.error_code, "error_message": record.error_message,
                }, now=current)
                cycle_submits += 1
                if self._state is not None:
                    self._state.day_trade_count += 1
                self._cooldown.record(candidate.stock_code, checked.side, current)
                if record.order_id:
                    if self._state is not None:
                        self._state.open_order_ids.append(record.order_id)
                if self._state is not None:
                    self._state.save(self._state_path(trade_date), self._cooldown)
                if record.status != "SUBMITTED":
                    frozen_codes.add(candidate.stock_code)
                    if getattr(record, "error_code", "") in {"113", "114", "115", "116", "404"}:
                        self._live_halted = True
                        tick.errors.append(f"STOP_RUN: trade {record.error_code}，后续切换 dry-run")
                    continue
                # 本轮后续判断使用本地活动委托，避免重复买卖。
                orders.append(MxOrder(
                    order_id=record.order_id,
                    code=checked.stock_code,
                    side=checked.side,
                    price=checked.limit_price,
                    qty=checked.qty,
                    filled_qty=0,
                    status="2",
                ))

        if tick.submits:
            balance_after, positions_after, orders_after, post_error, post_stop = self._account(run_id)
            if post_error:
                tick.errors.append("POST_QUERY_FAILED:" + post_error)
                if post_stop:
                    self._live_halted = True
                tick.status = "BLOCKED"
            else:
                tick.account["after"] = self._account_snapshot(balance_after, positions_after, orders_after)
                events.emit("ACCOUNT", cycle=self._cycle, detail={
                    "phase": "after",
                    "total_assets": balance_after.total_assets,
                    "avail_balance": balance_after.avail_balance,
                    "positions": len(positions_after),
                    "open_orders": len(orders_after),
                }, now=self.now_fn())
        if self._live_halted:
            tick.errors.append("LIVE_DISABLED_AFTER_STOP_RUN")
            tick.live = False
        if tick.errors and tick.status == "OK":
            tick.status = "DEGRADED"
        events.emit("CYCLE", cycle=self._cycle, detail={
            "phase": "end", "run_id": run_id, "status": tick.status, "live": tick.live,
            "bars": len(tick.new_closed_bars), "signals": len(tick.signals),
            "submits": len(tick.submits), "errors": len(tick.errors),
        }, now=self.now_fn())
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
                ticks.append(self.run_once(
                    signal_date,
                    live=live,
                    confirm=confirm,
                    candidates_override=candidates_override,
                ))
                cycle += 1
                if max_cycles is not None and cycle >= max_cycles:
                    break
                self.sleep_fn(self.config.query_interval_sec)
        except KeyboardInterrupt:
            # Ctrl+C：不自动撤单，保留已落盘 tick 后优雅退出。
            pass
        return ticks
