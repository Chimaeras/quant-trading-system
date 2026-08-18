"""PaperRun 主流程：健康检查→候选→行情→账户→研判→风控→（dry-run 停止 | confirm 逐笔下单）。

dry-run 绝不调用 trade/cancel；run --confirm 同步逐笔、禁并发、
trade 超时先查 orders 不重发。
"""
from __future__ import annotations

from dataclasses import asdict
from datetime import datetime

from .artifacts import config_hash, write_run
from .broker_client import BrokerClient
from .candidates import load_candidates
from .config import PaperConfig
from .decide import decide
from .models import Intent, IntentType, PaperRun, RiskStatus, SubmitRecord
from .quote_client import QuoteClient
from .risk import check_intent


def _new_run_id(now: datetime) -> str:
    return now.strftime("%Y%m%dT%H%M%S")


def _account_snapshot(balance, positions, orders) -> dict:
    return {
        "balance": {"total_assets": balance.total_assets, "avail_balance": balance.avail_balance,
                    "frozen": balance.frozen, "market_value": balance.market_value},
        "positions": [asdict(p) for p in positions],
        "orders": [asdict(o) for o in orders],
    }


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
    ):
        self._config = config
        self._quotes = quote_client
        self._broker = broker
        self._log_base = log_base
        self._now = now or datetime.now()

    def _prepare(self, signal_date: str, mode: str, candidates_override=None) -> PaperRun | tuple[PaperRun, str]:
        """公共前置：健康检查、候选、行情、账户快照、研判。返回 run 或 (run, abort_reason)。"""
        run = PaperRun(
            run_id=_new_run_id(self._now),
            mode=mode,
            signal_date=signal_date,
            trade_date=self._now.strftime("%Y-%m-%d"),
            config_snapshot=self._config.public_snapshot(),
        )

        # 风控闸 1：绑定/账户健康
        balance, result = self._broker.balance(run.run_id)
        if balance is None:
            run.errors.append(f"balance 失败 code={result.code} kind={result.kind.value} msg={result.message}")
            return run, "BALANCE_FAILED"

        # 候选
        if candidates_override is not None:
            candidates = candidates_override[: self._config.max_observe_codes]
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
        run.candidates = [asdict(c) for c in candidates]

        # 行情（逐只；失败只影响该票）
        for cand in candidates:
            quote, q_result = self._quotes.snapshot(
                cand.stock_code, cand.stock_name, run_id=run.run_id, received_at=self._now
            )
            if quote is None:
                run.errors.append(f"quote {cand.stock_code} 失败: {q_result.message}")
                if q_result.kind.stop_run():
                    return run, "QUERY_STOP"
                continue
            run.quotes[cand.stock_code] = asdict(quote)

        # 持仓与委托
        positions, _p = self._broker.positions(run.run_id)
        orders, _o = self._broker.orders(run.run_id)
        run.account_before = _account_snapshot(balance, positions, orders)
        self._balance = balance
        self._positions_qty = {p.code: p.qty for p in positions}
        self._open_orders = orders

        # 研判
        from .models import Candidate as CandidateModel, Quote as QuoteModel, QuoteValidity

        quote_objs = {}
        for code, payload in run.quotes.items():
            q = QuoteModel(**{k: v for k, v in payload.items() if k in QuoteModel.__dataclass_fields__})
            q.validity = QuoteValidity(payload.get("validity", "INVALID"))
            quote_objs[code] = q
        self._intents = decide(
            candidates, quote_objs, balance, self._positions_qty, orders, self._config, self._now
        )
        run.intents = [i.to_dict() for i in self._intents]
        return run, None

    def dry_run(self, signal_date: str, candidates_override=None) -> tuple[PaperRun, str | None]:
        """dry-run：到研判为止，绝不调用 trade。"""
        run, abort = self._prepare(signal_date, "dry-run", candidates_override)
        if abort:
            write_run(self._log_base, run, self._config)
            return run, abort
        # dry-run 预览：以 confirmed=True 模拟跑闸，展示真实拦截原因
        # （实际提交仍必须走 run_confirm 的人工确认码）
        for idx, intent in enumerate(self._intents):
            from .models import Quote as QuoteModel, QuoteValidity

            quote_obj = None
            payload = run.quotes.get(intent.stock_code)
            if payload:
                quote_obj = QuoteModel(**{k: v for k, v in payload.items() if k in QuoteModel.__dataclass_fields__})
                quote_obj.validity = QuoteValidity(payload.get("validity", "INVALID"))
            checked = check_intent(
                intent, quote_obj, self._balance, self._open_orders, self._config,
                0, {c["stock_code"] for c in run.candidates}, confirmed=True,
            )
            run.intents[idx] = checked.to_dict()
            if checked.risk_status == RiskStatus.REJECT:
                run.risk_rejects.append(
                    {"stock_code": checked.stock_code, "reason": checked.risk_reject_reason}
                )
        write_run(self._log_base, run, self._config)
        return run, None

    def run_confirm(
        self, signal_date: str, confirm_ack: str, candidates_override=None
    ) -> tuple[PaperRun, str | None]:
        """run --confirm：确认码匹配后逐笔同步 trade。

        Args:
            confirm_ack: 用户输入的确认码（须等于 config_hash，由 CLI 负责展示与收集）。
        """
        if confirm_ack != config_hash(self._config):
            return PaperRun(run_id=_new_run_id(self._now), mode="run", signal_date=signal_date,
                            trade_date=self._now.strftime("%Y-%m-%d")), "CONFIRM_MISMATCH"
        run, abort = self._prepare(signal_date, "run", candidates_override)
        if abort:
            write_run(self._log_base, run, self._config)
            return run, abort

        whitelist = {c["stock_code"] for c in run.candidates}
        trades_done = 0
        frozen_codes: set[str] = set()
        from .models import Quote as QuoteModel, QuoteValidity

        def _quote_obj(code: str):
            payload = run.quotes.get(code)
            if not payload:
                return None
            q = QuoteModel(**{k: v for k, v in payload.items() if k in QuoteModel.__dataclass_fields__})
            q.validity = QuoteValidity(payload.get("validity", "INVALID"))
            return q

        for idx, intent in enumerate(self._intents):
            if intent.stock_code in frozen_codes:
                intent.risk_status = RiskStatus.FROZEN
                intent.risk_reject_reason = "FROZEN_PREVIOUS_UNKNOWN"
                run.intents[idx] = intent.to_dict()
                continue
            quote_obj = _quote_obj(intent.stock_code)
            checked = check_intent(
                intent, quote_obj, self._balance, self._open_orders, self._config,
                trades_done, whitelist, confirmed=True,
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
                # 本地余额扣减，供后续闸 8/9 估算
                self._balance.avail_balance -= record.qty * (record.price or 0)
            elif record.status == "UNKNOWN_SUBMIT":
                frozen_codes.add(checked.stock_code)

        # 收尾：再拉账户
        balance2, _b = self._broker.balance(run.run_id)
        positions2, _p2 = self._broker.positions(run.run_id)
        orders2, _o2 = self._broker.orders(run.run_id)
        if balance2 is not None:
            run.account_after = _account_snapshot(balance2, positions2, orders2)
        write_run(self._log_base, run, self._config)
        return run, None

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
        if result.kind.stop_run():
            record.status = "REJECTED"
            record.error_code = result.code
            record.error_message = result.message
            self._run_stopped = True
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
        matches = [o for o in orders if o.code == intent.stock_code and o.qty == intent.qty]
        if len(matches) == 1:
            return matches[0].order_id
        return None
