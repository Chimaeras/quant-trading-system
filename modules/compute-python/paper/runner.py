"""PaperRun 主流程：健康检查→候选→行情→账户→研判→风控→（dry-run 停止 | confirm 逐笔下单）。

P0 修复后约束：
- balance/positions/orders 任一查询失败 → BLOCKED，绝不提交（P0-02 fail-closed）。
- stop_run 触发后剩余意图全部 SKIPPED_RUN_STOPPED，不再调用 trade/cancel（P0-03）。
- confirm 只执行 dry-run 生成的不可变 Preview 计划，凭一次性 confirm_token，
  不重新计算策略、不重新生成 intents（P0-04）。
"""
from __future__ import annotations

import hashlib
import json
import secrets
from dataclasses import asdict
from datetime import datetime, timedelta
from pathlib import Path

from .artifacts import config_hash, run_dir, write_run
from .broker_client import BrokerClient
from .candidates import dedupe_candidates, load_candidates
from .config import PaperConfig
from .decide import decide, in_trading_window
from .models import Intent, IntentType, MxOrder, PaperRun, RiskStatus, SubmitRecord
from .quote_client import QuoteClient
from .risk import check_intent, estimate_commission
from .trading_calendar import TradingCalendarService, UnavailableCalendar

CONFIRM_TTL_SEC = 600  # confirm_token 有效期（10 分钟）


def _new_run_id(now: datetime) -> str:
    """微秒时间戳 + 随机后缀，避免秒级碰撞导致 artifact 覆盖。"""
    return now.strftime("%Y%m%dT%H%M%S") + f"-{secrets.token_hex(3)}"


def _hash_json(payload) -> str:
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")).hexdigest()


def _account_snapshot(balance, positions, orders) -> dict:
    return {
        "balance": {"total_assets": balance.total_assets, "avail_balance": balance.avail_balance,
                    "frozen": balance.frozen, "market_value": balance.market_value},
        "positions": [asdict(p) for p in positions],
        "orders": [asdict(o) for o in orders],
    }


def _account_fingerprint(positions, orders) -> str:
    """持仓 + 活动委托 id 的指纹（confirm 与 preview 一致性校验用）。"""
    pos = sorted((p.code, p.qty) for p in positions)
    ords = sorted(o.order_id for o in orders)
    return _hash_json({"positions": pos, "orders": ords})


class PaperRunner:
    """一次 Paper Live 运行（构造后按 dry_run()/run_confirm() 驱动）。"""

    def __init__(
        self,
        config: PaperConfig,
        quote_client: QuoteClient,
        broker: BrokerClient,
        *,
        log_base,
        now: datetime | None = None,
        trading_calendar: TradingCalendarService | None = None,
    ):
        self._config = config
        self._quotes = quote_client
        self._broker = broker
        self._log_base = Path(log_base)
        self._now = now or datetime.now()
        # P1-03：日历不可用时 confirm 必须阻断，禁止默认按工作日放行
        self._calendar = trading_calendar or UnavailableCalendar()
        self._run_stopped = False
        self._stop_reason = ""
        self._preview: dict | None = None

    # ------------------------------------------------------------------ prepare

    def _prepare(self, signal_date: str, mode: str, candidates_override=None) -> PaperRun | tuple[PaperRun, str]:
        """公共前置：健康检查、候选、行情、账户快照、研判。返回 run 或 (run, abort_reason)。"""
        self._run_stopped = False
        self._stop_reason = ""
        run = PaperRun(
            run_id=_new_run_id(self._now),
            mode=mode,
            signal_date=signal_date,
            trade_date=self._now.strftime("%Y-%m-%d"),
            config_snapshot=self._config.public_snapshot(),
        )

        # 风控闸 1：绑定/账户健康（fail-closed）
        balance, result = self._broker.balance(run.run_id)
        if balance is None:
            run.errors.append(f"balance 失败 code={result.code if result else '?'} "
                              f"kind={result.kind.value if result else '?'} msg={result.message if result else '?'}")
            return run, "BALANCE_FAILED"

        # 候选
        if candidates_override is not None:
            candidates, dropped = dedupe_candidates(candidates_override)
            if dropped:
                run.errors.append(f"重复候选已去重（保留首个）: {sorted(set(dropped))}")
            candidates = candidates[: self._config.max_observe_codes]
            run.candidate_source = "file_override"
        else:
            candidates, source = load_candidates(
                signal_date,
                candidate_file=self._config.candidate_file,
                t1_base=self._config.t1_base,
                java_token=self._config.java_token,
                max_observe=self._config.max_observe_codes,
            )
            run.candidate_source = source
            if source == "t1_unavailable":
                # P1-07：主数据源失败不冒充“无候选”；dry-run 降级继续，confirm 阻断
                run.errors.append("T1_UNAVAILABLE: 主数据源查询失败，候选为空不可信")
        run.candidates = [asdict(c) for c in candidates]

        # 行情（逐只；失败只影响该票）
        for cand in candidates:
            quote, q_result = self._quotes.snapshot(
                cand.stock_code, cand.stock_name, run_id=run.run_id, received_at=self._now
            )
            if quote is None:
                run.errors.append(f"quote {cand.stock_code} 失败: {q_result.message if q_result else '?'}")
                if q_result is not None and q_result.stop_run():
                    return run, "QUERY_STOP"
                continue
            run.quotes[cand.stock_code] = asdict(quote)

        # 持仓与委托（P0-02：任一失败立即 BLOCKED，绝不把失败当空列表）
        positions, p_result = self._broker.positions(run.run_id)
        if positions is None or (p_result is not None and not p_result.ok):
            run.errors.append(f"positions 失败 kind={p_result.kind.value if p_result else '?'} "
                              f"msg={p_result.message if p_result else '?'}")
            return run, "POSITIONS_FAILED"
        orders, o_result = self._broker.orders(run.run_id)
        if orders is None or (o_result is not None and not o_result.ok):
            run.errors.append(f"orders 失败 kind={o_result.kind.value if o_result else '?'} "
                              f"msg={o_result.message if o_result else '?'}")
            return run, "ORDERS_FAILED"
        run.account_before = _account_snapshot(balance, positions, orders)
        # P1-01：撤单失败（status=10）等异常委托进入审计，不得静默忽略
        abnormal = [o for o in orders if o.status in ("10",)]
        if abnormal:
            run.errors.append(
                "ABNORMAL_ORDER_STATUS: " + "; ".join(f"{o.code}/{o.order_id}/status={o.status}" for o in abnormal)
            )
        self._balance = balance
        self._positions_qty = {p.code: p.qty for p in positions}
        self._open_orders = orders
        self._account_fingerprint = _account_fingerprint(positions, orders)

        # 研判
        from .models import Quote as QuoteModel, QuoteValidity

        quote_objs = {}
        for code, payload in run.quotes.items():
            q = QuoteModel(**{k: v for k, v in payload.items() if k in QuoteModel.__dataclass_fields__})
            q.validity = QuoteValidity(payload.get("validity", "INVALID"))
            quote_objs[code] = q
        self._intents = decide(
            candidates, quote_objs, balance, self._positions_qty, orders, self._config, self._now
        )
        # decide 可能补充行情字段（如涨停价推导），回写快照保证 preview 与闸一致
        run.quotes = {code: asdict(q) for code, q in quote_objs.items()}
        run.intents = [i.to_dict() for i in self._intents]
        return run, None

    # ------------------------------------------------------------------ dry-run

    def dry_run(self, signal_date: str, candidates_override=None) -> tuple[PaperRun, str | None]:
        """dry-run：到研判为止，绝不调用 trade；产出不可变 Preview + 一次性 confirm_token。"""
        run, abort = self._prepare(signal_date, "dry-run", candidates_override)
        if abort:
            run.status = "BLOCKED"
            write_run(self._log_base, run, self._config)
            return run, abort
        # dry-run 预览：以 confirmed=True 模拟跑闸，展示真实拦截原因
        for idx, intent in enumerate(self._intents):
            quote_obj = self._quote_from_run(run, intent.stock_code)
            checked = check_intent(
                intent, quote_obj, self._balance, self._open_orders, self._config,
                0, {c["stock_code"] for c in run.candidates}, confirmed=True,
                positions_qty=self._positions_qty,
            )
            run.intents[idx] = checked.to_dict()
            if checked.risk_status == RiskStatus.REJECT:
                run.risk_rejects.append(
                    {"stock_code": checked.stock_code, "reason": checked.risk_reject_reason}
                )
        run.status = "PREVIEW_READY"
        # P1-03：日历不可用/非交易日 → dry-run 告警降级，confirm 硬阻断
        if not self._calendar.available:
            run.errors.append(f"TRADING_CALENDAR_UNAVAILABLE(dry-run 告警): {self._calendar.unavailable_reason()}")
        elif not self._calendar.is_trading_day(self._now):
            run.errors.append(f"NOT_TRADING_DAY(dry-run 告警): {self._now.date().isoformat()} 非交易日（日历 {self._calendar.source}）")

        out = write_run(self._log_base, run, self._config)

        # P0-04：持久化不可变 Preview（confirm 只能执行这份计划）
        created_at = self._now
        preview = {
            "preview_run_id": run.run_id,
            "config_hash": config_hash(self._config),
            "candidate_hash": _hash_json(run.candidates),
            "quote_hash": _hash_json(run.quotes),
            "account_hash": self._account_fingerprint,
            "candidate_source": run.candidate_source,
            "intents": run.intents,
            "created_at": created_at.isoformat(),
            "confirm_expires_at": (created_at + timedelta(seconds=CONFIRM_TTL_SEC)).isoformat(),
            "confirm_token": secrets.token_hex(8),
            "used": False,
        }
        (out / "preview.json").write_text(
            json.dumps(preview, ensure_ascii=False, indent=2, default=str), encoding="utf-8"
        )
        self._preview = preview
        # 内存态附行情原文（不落盘），confirm 阶段重建 Quote 跑风控闸
        self._preview["_quotes"] = dict(run.quotes)
        return run, None

    def preview_token(self) -> str:
        """当前 preview 的一次性确认码（CLI 展示给用户）。"""
        if self._preview is None:
            return ""
        return self._preview["confirm_token"]

    # ------------------------------------------------------------------ confirm

    def run_confirm(
        self,
        signal_date: str,
        confirm_token: str,
        *,
        preview_run_id: str = "",
        candidates_override=None,
    ) -> tuple[PaperRun, str | None]:
        """run --confirm：校验一次性 token 后执行 Preview 不可变计划，逐笔同步 trade。

        不重新查询行情、不重新计算策略、不重新生成 intents（P0-04）；
        只重新做账户安全校验（balance/positions/orders fail-closed）与交易时段校验。
        """
        preview = self._load_preview(preview_run_id)
        run = PaperRun(
            run_id=_new_run_id(self._now), mode="run", signal_date=signal_date,
            trade_date=self._now.strftime("%Y-%m-%d"),
            config_snapshot=self._config.public_snapshot(),
        )
        if preview is None:
            return run, "PREVIEW_REQUIRED"
        if preview.get("used"):
            return run, "CONFIRM_TOKEN_REUSED"
        if confirm_token != preview["confirm_token"]:
            return run, "CONFIRM_TOKEN_MISMATCH"
        expires = datetime.fromisoformat(preview["confirm_expires_at"])
        if self._now > expires:
            return run, "CONFIRM_TOKEN_EXPIRED"
        if preview["config_hash"] != config_hash(self._config):
            return run, "CONFIG_CHANGED"
        run.preview_run_id = preview["preview_run_id"]
        # P1-07：主数据源失败生成的 preview 不得 confirm
        if preview.get("candidate_source") == "t1_unavailable":
            run.errors.append("DATA_SOURCE_BLOCKED: preview 候选来自失败的 T1 查询，须先恢复数据源并重新 dry-run")
            return run, "DATA_SOURCE_BLOCKED"
        # P1-03：交易日历不可用时 confirm 必须阻断
        if not self._calendar.available:
            run.errors.append(f"TRADING_CALENDAR_UNAVAILABLE: {self._calendar.unavailable_reason()}")
            return run, "TRADING_CALENDAR_UNAVAILABLE"

        # 账户安全校验（fail-closed；不重新取行情/候选）
        balance, b_result = self._broker.balance(run.run_id)
        if balance is None:
            run.errors.append(f"balance 失败 msg={b_result.message if b_result else '?'}")
            write_run(self._log_base, run, self._config)
            return run, "BALANCE_FAILED"
        positions, p_result = self._broker.positions(run.run_id)
        if positions is None or (p_result is not None and not p_result.ok):
            run.errors.append("positions 失败（confirm 阻断）")
            write_run(self._log_base, run, self._config)
            return run, "POSITIONS_FAILED"
        orders, o_result = self._broker.orders(run.run_id)
        if orders is None or (o_result is not None and not o_result.ok):
            run.errors.append("orders 失败（confirm 阻断）")
            write_run(self._log_base, run, self._config)
            return run, "ORDERS_FAILED"
        run.account_before = _account_snapshot(balance, positions, orders)

        # 账户变化校验：preview 后持仓/活动委托变化 → 要求重新 dry-run
        if _account_fingerprint(positions, orders) != preview["account_hash"]:
            run.errors.append("账户状态与 preview 不一致（持仓或活动委托变化），须重新 dry-run")
            write_run(self._log_base, run, self._config)
            return run, "ACCOUNT_CHANGED"

        # 交易时段与交易日校验（只校验，不重新生成计划）
        if not in_trading_window(self._now):
            run.errors.append("confirm 时不在连续竞价时段")
            write_run(self._log_base, run, self._config)
            return run, "TIME_WINDOW"
        if not self._calendar.is_trading_day(self._now):
            run.errors.append(f"NOT_TRADING_DAY: {self._now.date().isoformat()}（日历 {self._calendar.source}）")
            write_run(self._log_base, run, self._config)
            return run, "NOT_TRADING_DAY"

        # 执行 Preview 不可变计划（不重新 decide）
        self._balance = balance
        self._positions_qty = {p.code: p.qty for p in positions}
        self._open_orders = orders
        intents = [self._intent_from_dict(d) for d in preview["intents"]]
        run.intents = [d.copy() for d in preview["intents"]]
        whitelist = {c["stock_code"] for c in run.candidates} if run.candidates else {
            d["stock_code"] for d in preview["intents"]
        }
        self._run_stopped = False
        self._stop_reason = ""

        trades_done = 0
        frozen_codes: set[str] = set()
        run.status = "SUBMITTING"
        run.metrics = {"query_count": 3, "trade_count": 0, "cancel_count": 0,
                       "blocked_count": 0, "stop_run_count": 0, "reconcile_error_count": 0}
        for idx, intent in enumerate(intents):
            # P0-03：stop_run 后剩余意图全部跳过，绝不再提交
            if self._run_stopped:
                intent.risk_status = RiskStatus.REJECT
                intent.risk_reject_reason = "SKIPPED_RUN_STOPPED"
                intent.reason_codes.append("SKIPPED_RUN_STOPPED")
                run.intents[idx] = intent.to_dict()
                run.metrics["stop_run_count"] += 1
                continue
            if intent.stock_code in frozen_codes:
                intent.risk_status = RiskStatus.FROZEN
                intent.risk_reject_reason = "FROZEN_PREVIOUS_UNKNOWN"
                run.intents[idx] = intent.to_dict()
                continue
            quote_obj = self._quote_from_preview(preview, intent.stock_code)
            checked = check_intent(
                intent, quote_obj, self._balance, self._open_orders, self._config,
                trades_done, whitelist, confirmed=True,
                positions_qty=self._positions_qty,
            )
            run.intents[idx] = checked.to_dict()
            if checked.intent_type != IntentType.BUY_LIMIT or checked.risk_status != RiskStatus.PASS:
                if checked.risk_reject_reason:
                    run.risk_rejects.append({"stock_code": checked.stock_code, "reason": checked.risk_reject_reason})
                continue

            # 逐笔同步提交（禁并发）
            record = self._submit(checked, run)
            run.submits.append(asdict(record))
            if record.status == "SUBMITTED":
                trades_done += 1
                run.metrics["trade_count"] += 1
                # 本地余额扣减（含佣金预留），供后续闸 8/9 估算
                gross = record.qty * (record.price or 0)
                self._balance.avail_balance -= gross + estimate_commission(gross)
                # 提交成功后立即加入本地活动委托集合，防止同轮重复下单
                self._open_orders = list(self._open_orders) + [
                    MxOrder(order_id=record.order_id, code=record.stock_code, side=record.side,
                            price=record.price, qty=record.qty, filled_qty=0, status="2")
                ]
            elif record.status == "UNKNOWN_SUBMIT":
                frozen_codes.add(checked.stock_code)

        # 收尾：trade 后必须成功查询账户（P0-02：失败则结果不是 SUCCESS）
        balance2, b2 = self._broker.balance(run.run_id)
        positions2, p2 = self._broker.positions(run.run_id)
        orders2, o2 = self._broker.orders(run.run_id)
        post_failed = balance2 is None or positions2 is None or orders2 is None or (
            (b2 is not None and not b2.ok) or (p2 is not None and not p2.ok) or (o2 is not None and not o2.ok)
        )
        if balance2 is not None and positions2 is not None:
            run.account_after = _account_snapshot(balance2, positions2, orders2 or [])
        abort = None
        if post_failed:
            run.errors.append("POST_QUERY_FAILED: trade 后账户查询失败，结果记为 UNKNOWN（非 SUCCESS）")
            abort = "POST_QUERY_FAILED"
        # P2-04 运行级终态
        run.metrics["blocked_count"] = len(run.risk_rejects)
        if abort:
            run.status = "UNKNOWN"
        elif self._run_stopped:
            run.status = "STOPPED"
        elif run.submits and any(s["status"] == "UNKNOWN_SUBMIT" for s in run.submits):
            run.status = "UNKNOWN"
        elif run.submits:
            run.status = "SUCCESS"
        else:
            run.status = "BLOCKED"

        # token 失效（成功或明确拒绝后均不可重放）
        self._invalidate_token(preview)

        write_run(self._log_base, run, self._config)
        return run, abort

    # ------------------------------------------------------------------ helpers

    def _quote_from_run(self, run: PaperRun, code: str):
        from .models import Quote as QuoteModel, QuoteValidity

        payload = run.quotes.get(code)
        if not payload:
            return None
        q = QuoteModel(**{k: v for k, v in payload.items() if k in QuoteModel.__dataclass_fields__})
        q.validity = QuoteValidity(payload.get("validity", "INVALID"))
        return q

    def _quote_from_preview(self, preview: dict, code: str):
        from .models import Quote as QuoteModel, QuoteValidity

        payload = (preview.get("_quotes") or {}).get(code)
        if not payload:
            return None
        q = QuoteModel(**{k: v for k, v in payload.items() if k in QuoteModel.__dataclass_fields__})
        q.validity = QuoteValidity(payload.get("validity", "INVALID"))
        return q

    @staticmethod
    def _intent_from_dict(d: dict) -> Intent:
        return Intent(
            stock_code=d["stock_code"],
            intent_type=IntentType(d["intent_type"]),
            side=d.get("side", "buy"),
            qty=d.get("qty", 0),
            price_type=d.get("price_type", "limit"),
            limit_price=d.get("limit_price"),
            reason_codes=list(d.get("reason_codes", [])),
            quote_ts=d.get("quote_ts", ""),
            candidate_source=d.get("candidate_source", ""),
            risk_status=RiskStatus(d.get("risk_status", "PENDING")),
            risk_reject_reason=d.get("risk_reject_reason", ""),
        )

    def _load_preview(self, preview_run_id: str) -> dict | None:
        """加载 preview：优先内存（同进程 CLI 流程），否则按 preview_run_id 从磁盘检索。"""
        if self._preview is not None:
            if not preview_run_id or self._preview["preview_run_id"] == preview_run_id:
                return self._preview
        if not preview_run_id:
            return None
        for path in sorted(self._log_base.glob(f"paper/*/{preview_run_id}/preview.json")):
            try:
                preview = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            # 磁盘 preview 不含行情原文，quote 对象由 preview intents 内字段重建
            preview.setdefault("_quotes", {})
            return preview
        return None

    def _invalidate_token(self, preview: dict) -> None:
        """token 一次性：用后即失效（内存 + 磁盘 preview.json）。"""
        preview["used"] = True
        if self._preview is not None and self._preview["preview_run_id"] == preview["preview_run_id"]:
            self._preview["used"] = True
        rid = preview["preview_run_id"]
        for path in self._log_base.glob(f"paper/*/{rid}/preview.json"):
            try:
                on_disk = json.loads(path.read_text(encoding="utf-8"))
                on_disk["used"] = True
                path.write_text(json.dumps(on_disk, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
            except (OSError, ValueError):
                continue

    def _submit(self, intent: Intent, run: PaperRun) -> SubmitRecord:
        """单笔提交与超时对账（不重发）。"""
        result = self._broker.trade(
            intent.stock_code, intent.side, intent.qty,
            use_market_price=False, price=intent.limit_price, run_id=run.run_id,
        )
        record = SubmitRecord(
            stock_code=intent.stock_code, side=intent.side, qty=intent.qty,
            price=intent.limit_price, ok=result.ok, request_id=result.request_id, raw=result.raw,
        )
        if result.ok and isinstance(result.data, dict):
            order_id = result.data.get("orderId") or (result.data.get("data") or {}).get("orderId")
            if order_id:
                record.order_id = str(order_id)
                record.status = "SUBMITTED"
                return record
            # 成功但无 orderId：查 orders 匹配（代码+方向+数量）
            matched = self._match_recent_order(intent, run)
            if matched:
                record.order_id = matched
                record.status = "SUBMITTED"
                return record
            record.status = "UNKNOWN_SUBMIT"
            record.error_message = "成功包络无 orderId 且 orders 无唯一匹配"
            return record
        if result.stop_run():
            record.status = "REJECTED"
            record.error_code = result.code
            record.error_message = result.message
            # P0-03：停止性错误立即终止本轮剩余意图
            self._run_stopped = True
            self._stop_reason = f"code={result.code} kind={result.kind.value} msg={result.message}"
            run.errors.append(f"STOP_RUN {self._stop_reason}")
            return record
        # 超时/网络错误：先查 orders，禁止重发
        matched = self._match_recent_order(intent, run)
        if matched:
            record.order_id = matched
            record.status = "SUBMITTED"
            record.ok = True
            record.error_message = "超时后经 orders 匹配补记"
        else:
            record.status = "UNKNOWN_SUBMIT"
            record.error_code = result.code
            record.error_message = f"{result.message}（已查 orders 无唯一匹配，冻结不重发）"
        return record

    def _match_recent_order(self, intent: Intent, run: PaperRun) -> str | None:
        """按代码+数量在 orders 中找唯一匹配的最近委托。"""
        orders, _ = self._broker.orders(run.run_id)
        if not orders:
            return None
        matches = [o for o in orders if o.code == intent.stock_code and o.qty == intent.qty]
        if len(matches) == 1:
            return matches[0].order_id
        return None
