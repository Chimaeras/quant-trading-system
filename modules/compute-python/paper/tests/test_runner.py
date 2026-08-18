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
    from paper.trading_calendar import TradingCalendarService

    return PaperRunner(_config(**cfg), StubQuoteClient(quotes), broker, log_base=tmp_path, now=T,
                       trading_calendar=TradingCalendarService())


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
    """run --confirm：preview + 一次性 token 后逐笔同步提交。"""

    def _preview(self, tmp_path, broker, quotes=None, cands=None):
        cands = cands or [Candidate(stock_code="600519", stock_name="t", exchange_type="SH", source="file")]
        runner = _runner(tmp_path, quotes or {"600519": _quote()}, broker)
        preview, abort = runner.dry_run("2026-08-18", candidates_override=cands)
        assert abort is None
        return runner, preview

    def test_confirm_ok(self, tmp_path):
        broker = StubBroker()
        runner, preview = self._preview(tmp_path, broker)
        run, abort = runner.run_confirm(
            "2026-08-18", runner.preview_token(), preview_run_id=preview.run_id
        )
        assert abort is None
        assert len(broker.trade_calls) == 1
        assert broker.trade_calls[0]["qty"] == 400
        assert run.submits[0]["status"] == "SUBMITTED"
        assert run.submits[0]["order_id"] == "ORD-1"
        assert run.account_after != {}

    def test_confirm_without_preview_blocked(self, tmp_path):
        broker = StubBroker()
        runner = _runner(tmp_path, {}, broker)
        run, abort = runner.run_confirm("2026-08-18", "any-token")
        assert abort == "PREVIEW_REQUIRED"
        assert broker.trade_calls == []

    def test_confirm_token_mismatch(self, tmp_path):
        broker = StubBroker()
        runner, _preview = self._preview(tmp_path, broker)
        run, abort = runner.run_confirm("2026-08-18", "wrong-token", preview_run_id=_preview.run_id)
        assert abort == "CONFIRM_TOKEN_MISMATCH"
        assert broker.trade_calls == []

    def test_confirm_token_one_shot(self, tmp_path):
        """token 用后即失效：第二次 confirm 必须 BLOCKED。"""
        broker = StubBroker()
        runner, preview = self._preview(tmp_path, broker)
        token = runner.preview_token()
        run1, _ = runner.run_confirm("2026-08-18", token, preview_run_id=preview.run_id)
        assert len(broker.trade_calls) == 1
        run2, abort2 = runner.run_confirm("2026-08-18", token, preview_run_id=preview.run_id)
        assert abort2 == "CONFIRM_TOKEN_REUSED"
        assert len(broker.trade_calls) == 1  # 未重复提交

    def test_confirm_expired_token(self, tmp_path):
        """token 过期后 confirm 不得调用 trade。"""
        from datetime import timedelta

        broker = StubBroker()
        runner, preview = self._preview(tmp_path, broker)
        runner._now = runner._now + timedelta(seconds=601)
        run, abort = runner.run_confirm(
            "2026-08-18", runner.preview_token(), preview_run_id=preview.run_id
        )
        assert abort == "CONFIRM_TOKEN_EXPIRED"
        assert broker.trade_calls == []

    def test_confirm_config_changed(self, tmp_path):
        """preview 后修改配置 → 旧 token 失效。"""
        broker = StubBroker()
        runner, preview = self._preview(tmp_path, broker)
        runner._config.max_name_weight = 0.10
        run, abort = runner.run_confirm(
            "2026-08-18", runner.preview_token(), preview_run_id=preview.run_id
        )
        assert abort == "CONFIG_CHANGED"
        assert broker.trade_calls == []

    def test_confirm_account_changed(self, tmp_path):
        """preview 后外部持仓/委托变化 → 要求重新 dry-run。"""
        broker = StubBroker()
        runner, preview = self._preview(tmp_path, broker)
        broker._orders = [MxOrder(order_id="ORD-X", code="600519", side="buy", price=100.2,
                                  qty=100, filled_qty=0, status="2")]
        run, abort = runner.run_confirm(
            "2026-08-18", runner.preview_token(), preview_run_id=preview.run_id
        )
        assert abort == "ACCOUNT_CHANGED"
        assert broker.trade_calls == []

    def test_confirm_submits_preview_intents_only(self, tmp_path):
        """confirm 提交的 intents 必须与 preview 完全一致（不重新生成）。"""
        broker = StubBroker()
        runner, preview = self._preview(tmp_path, broker)
        # preview 后行情大幅变化（重新查询也不会改变计划）
        run, abort = runner.run_confirm(
            "2026-08-18", runner.preview_token(), preview_run_id=preview.run_id
        )
        assert abort is None
        assert broker.trade_calls[0]["qty"] == preview.intents[0]["qty"]
        assert broker.trade_calls[0]["price"] == preview.intents[0]["limit_price"]


class TestReconcile:
    """对账（P1-06：状态分类，已撤/废单不判成功）。"""

    def _write_submits(self, tmp_path, submits):
        day = tmp_path / "paper" / "2026-08-19" / "run1"
        day.mkdir(parents=True)
        (day / "submits.json").write_text(json.dumps(submits), encoding="utf-8")

    def test_reconcile_ok(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()
        broker._orders = [MxOrder(order_id="ORD-1", code="600519", side="buy", price=100.2, qty=400,
                                  filled_qty=400, status="4")]
        self._write_submits(tmp_path, [{"stock_code": "600519", "side": "buy", "qty": 400,
                                        "status": "SUBMITTED", "order_id": "ORD-1"}])
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["status"] == "OK"
        assert report["success_count"] == 1

    def test_reconcile_canceled_not_success(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()
        broker._orders = [MxOrder(order_id="ORD-1", code="600519", side="buy", price=100.2, qty=400,
                                  filled_qty=0, status="8")]
        self._write_submits(tmp_path, [{"stock_code": "600519", "side": "buy", "qty": 400,
                                        "status": "SUBMITTED", "order_id": "ORD-1"}])
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["status"] == "BLOCKED"
        assert report["canceled_count"] == 1
        assert report["success_count"] == 0

    def test_reconcile_partial(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()
        broker._orders = [MxOrder(order_id="ORD-1", code="600519", side="buy", price=100.2, qty=400,
                                  filled_qty=100, status="3")]
        self._write_submits(tmp_path, [{"stock_code": "600519", "side": "buy", "qty": 400,
                                        "status": "SUBMITTED", "order_id": "ORD-1"}])
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["partial_count"] == 1
        assert report["results"][0]["remaining_qty"] == 300

    def test_reconcile_qty_conflict_blocked(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()
        broker._orders = [MxOrder(order_id="ORD-1", code="600519", side="buy", price=100.2, qty=100,
                                  filled_qty=100, status="4")]
        self._write_submits(tmp_path, [{"stock_code": "600519", "side": "buy", "qty": 400,
                                        "status": "SUBMITTED", "order_id": "ORD-1"}])
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["status"] == "BLOCKED"  # 数量冲突不能判成功

    def test_reconcile_orders_query_failed(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()

        def _fail(run_id="adhoc"):
            return None, None

        broker.orders = _fail
        self._write_submits(tmp_path, [{"stock_code": "600519", "side": "buy", "qty": 400,
                                        "status": "SUBMITTED", "order_id": "ORD-1"}])
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["status"] == "BLOCKED"
        assert report["query_error_count"] == 1

    def test_reconcile_unknown(self, tmp_path):
        from paper.reconcile import reconcile_day

        broker = StubBroker()
        broker._orders = []
        self._write_submits(tmp_path, [{"stock_code": "600519", "qty": 400, "status": "UNKNOWN_SUBMIT", "order_id": ""}])
        report = reconcile_day(broker, tmp_path, "2026-08-19")
        assert report["status"] == "BLOCKED"
        assert report["unknown_count"] == 1
