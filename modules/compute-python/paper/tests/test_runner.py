"""runner / artifacts / reconcile 集成测试（stub 客户端，全离线）。"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from paper.artifacts import config_hash, run_dir
from paper.config import PaperConfig
from paper.models import Balance, Candidate, MxOrder, Quote, QuoteValidity
from paper.runner import PaperRunner

T = datetime(2026, 8, 19, 9, 35)


class StubQuoteClient:
    """行情 stub：预置 Quote，不联网。"""

    def __init__(self, quotes: dict[str, Quote]):
        self._quotes = quotes
        self.calls: list[str] = []

    def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
        self.calls.append(code)
        q = self._quotes.get(code)
        if q is None:
            from paper.mx_result import MxErrorKind, MxResult

            return None, MxResult(endpoint="/query", ok=False, code="E", message="无行情", kind=MxErrorKind.BIZ_ERROR)
        return q, None


class StubBroker:
    """broker stub：记录 trade/cancel 调用。"""

    def __init__(self, balance: Balance | None = None, orders: list[MxOrder] | None = None,
                 trade_result_ok: bool = True):
        self._balance = balance or Balance(200_000, 200_000)
        self._orders = orders or []
        self._trade_ok = trade_result_ok
        self.trade_calls: list[dict] = []
        self.cancel_calls: list[dict] = []

    def balance(self, run_id="adhoc"):
        return self._balance, None

    def positions(self, run_id="adhoc"):
        return [], None

    def orders(self, run_id="adhoc"):
        return list(self._orders), None

    def trade(self, code, side, qty, *, use_market_price=False, price=None, run_id="adhoc"):
        self.trade_calls.append({"code": code, "side": side, "qty": qty, "price": price})
        from paper.mx_result import MxResult

        if self._trade_ok:
            return MxResult(endpoint="/trade", ok=True, code="200", message="成功",
                            data={"orderId": "ORD-1"}, raw={"data": {"orderId": "ORD-1"}})
        return MxResult(endpoint="/trade", ok=False, code="500", message="失败")

    def cancel_order(self, order_id, stock_code="", run_id="adhoc"):
        self.cancel_calls.append({"order_id": order_id})
        from paper.mx_result import MxResult

        return MxResult(endpoint="/cancel", ok=True, code="200", message="成功")

    def cancel_all(self, run_id="adhoc"):
        self.cancel_calls.append({"all": True})
        from paper.mx_result import MxResult

        return MxResult(endpoint="/cancel", ok=True, code="200", message="成功")


def _config(**kw) -> PaperConfig:
    base = dict(api_key="k", max_name_weight=0.20)
    base.update(kw)
    return PaperConfig(**base)


def _quote(code="600519", last=100.0) -> Quote:
    return Quote(code=code, name="t", exchange="SH", last=last, pct_chg=1.0,
                 volume=1000, market_time=T, received_at=T, validity=QuoteValidity.VALID)


def _runner(tmp_path, quotes, broker, **cfg):
    return PaperRunner(_config(**cfg), StubQuoteClient(quotes), broker, log_base=tmp_path, now=T)


class TestDryRun:
    """dry-run 绝不发单（验收：无 trade 请求）。"""

    def test_dry_run_no_trade(self, tmp_path):
        broker = StubBroker()
        cands = [Candidate(stock_code="600519", stock_name="t", exchange_type="SH", source="file")]
        runner = _runner(tmp_path, {"600519": _quote()}, broker)
        run, abort = runner.dry_run("2026-08-18", candidates_override=cands)
        assert abort is None
        assert broker.trade_calls == []
        assert run.intents[0]["intent_type"] == "BUY_LIMIT"
        # artifacts 已落盘
        out = run_dir(tmp_path, run.trade_date, run.run_id)
        assert (out / "manifest.json").exists()
        manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
        assert manifest["contains_secret"] is False
        assert "api_key" not in json.dumps(manifest)
        assert (out / "intents.json").exists()
        assert (out / "daily.md").exists()

    def test_dry_run_balance_abort(self, tmp_path):
        broker = StubBroker()
        broker._balance = None

        def _fail(run_id="adhoc"):
            from paper.mx_result import MxErrorKind, MxResult

            return None, MxResult(endpoint="/balance", ok=False, code="404",
                                  message="请先绑定账户~", kind=MxErrorKind.NOT_BOUND)

        broker.balance = _fail
        runner = _runner(tmp_path, {}, broker)
        run, abort = runner.dry_run("2026-08-18", candidates_override=[])
        assert abort == "BALANCE_FAILED"


class TestRunConfirm:
    """run --confirm 逐笔同步提交。"""

    def test_confirm_ok(self, tmp_path):
        broker = StubBroker()
        cands = [Candidate(stock_code="600519", stock_name="t", exchange_type="SH", source="file")]
        runner = _runner(tmp_path, {"600519": _quote()}, broker)
        run, abort = runner.run_confirm(
            "2026-08-18", config_hash(runner._config), candidates_override=cands
        )
        assert abort is None
        assert len(broker.trade_calls) == 1
        assert broker.trade_calls[0]["qty"] == 400
        assert run.submits[0]["status"] == "SUBMITTED"
        assert run.submits[0]["order_id"] == "ORD-1"

    def test_confirm_mismatch(self, tmp_path):
        broker = StubBroker()
        runner = _runner(tmp_path, {}, broker)
        run, abort = runner.run_confirm("2026-08-18", "wrong-ack", candidates_override=[])
        assert abort == "CONFIRM_MISMATCH"
        assert broker.trade_calls == []

    def test_duplicate_second_run(self, tmp_path):
        """重复 run：第一笔在外部委托中 → DUPLICATE_ORDER。"""
        broker = StubBroker()
        cands = [Candidate(stock_code="600519", stock_name="t", exchange_type="SH", source="file")]
        runner = _runner(tmp_path, {"600519": _quote()}, broker)
        run1, _ = runner.run_confirm("2026-08-18", config_hash(runner._config), candidates_override=cands)
        assert len(broker.trade_calls) == 1
        # 第二次：外部已有同标的委托
        broker._orders = [MxOrder(order_id="ORD-1", code="600519", side="buy", price=100.2, qty=400,
                                  filled_qty=0, status="待成交")]
        runner2 = _runner(tmp_path, {"600519": _quote()}, broker)
        run2, _ = runner2.run_confirm("2026-08-18", config_hash(runner2._config), candidates_override=cands)
        assert run2.intents[0]["intent_type"] == "NOOP"
        assert run2.intents[0]["reason_codes"] == ["DUPLICATE_ORDER"]
        assert len(broker.trade_calls) == 1  # 未重复下单


class TestReconcile:
    """对账。"""

    def test_reconcile_ok(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()
        broker._orders = [MxOrder(order_id="ORD-1", code="600519", side="buy", price=100.2, qty=400,
                                  filled_qty=0, status="已成")]
        # 预置 submits
        day = tmp_path / "paper" / "2026-08-19" / "run1"
        day.mkdir(parents=True)
        (day / "submits.json").write_text(
            json.dumps([{"stock_code": "600519", "qty": 400, "status": "SUBMITTED", "order_id": "ORD-1"}]),
            encoding="utf-8",
        )
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["status"] == "OK"
        assert report["matched"] == 1

    def test_reconcile_unknown(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()
        broker._orders = []
        day = tmp_path / "paper" / "2026-08-19" / "run1"
        day.mkdir(parents=True)
        (day / "submits.json").write_text(
            json.dumps([{"stock_code": "600519", "qty": 400, "status": "UNKNOWN_SUBMIT", "order_id": ""}]),
            encoding="utf-8",
        )
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["status"] == "BLOCKED"
        assert report["unknown"] == 1
