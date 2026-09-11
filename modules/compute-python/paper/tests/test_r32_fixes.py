"""R32 回归测试：对账 CLI 崩溃、账户查询 None、max_retry=0、委托误匹配、preview 跨进程行情。

全部离线（stub 客户端），不发任何真实请求。
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime

import pytest

from paper.config import PaperConfig
from paper.models import Candidate, Intent, IntentType, MxOrder
from paper.runner import PaperRunner
from paper.tests.test_runner import StubBroker, StubQuoteClient, _config, _quote

T = datetime(2026, 8, 19, 9, 35)


def _runner(tmp_path, quotes, broker):
    from paper.trading_calendar import TradingCalendarService

    return PaperRunner(_config(), StubQuoteClient(quotes), broker, log_base=tmp_path, now=T,
                       trading_calendar=TradingCalendarService())


# --------------------------------------------------------------- cli.cmd_account

class _BrokenQueryBroker(StubBroker):
    """positions/orders 查询失败：官方字段严格解析，失败即返回 (None, result)。"""

    def positions(self, run_id="adhoc"):
        from paper.mx_result import MxErrorKind, MxResult

        return None, MxResult(endpoint="/positions", ok=False, code="500",
                              message="positions 查询失败", kind=MxErrorKind.HTTP_ERROR)

    def orders(self, run_id="adhoc"):
        from paper.mx_result import MxErrorKind, MxResult

        return None, MxResult(endpoint="/orders", ok=False, code="500",
                              message="orders 查询失败", kind=MxErrorKind.HTTP_ERROR)


class TestAccountCommand:
    def _patch_clients(self, monkeypatch, broker):
        from paper import cli

        monkeypatch.setattr(cli, "_load_config", lambda: _config())
        monkeypatch.setattr(cli, "_build_clients", lambda _c: (None, None, broker))

    def test_account_survives_positions_and_orders_none(self, monkeypatch, capsys):
        from paper import cli

        self._patch_clients(monkeypatch, _BrokenQueryBroker())
        # 修复前：len(None) → TypeError 直接把命令打崩
        rc = cli.cmd_account(argparse.Namespace())

        assert rc == 1
        err = capsys.readouterr().err
        assert "持仓查询失败" in err
        assert "委托查询失败" in err


# -------------------------------------------------------------- cli.cmd_reconcile

class TestReconcileCommand:
    def _patch(self, monkeypatch, tmp_path, broker):
        from paper import cli

        monkeypatch.setattr(cli, "_load_config", lambda: _config())
        monkeypatch.setattr(cli, "_build_clients", lambda _c: (None, None, broker))
        monkeypatch.setattr(cli, "LOG_BASE", tmp_path)

    def test_reconcile_command_prints_unknown_count_without_keyerror(self, monkeypatch, tmp_path, capsys):
        from paper import cli

        broker = StubBroker()
        broker._orders = []
        day = tmp_path / "paper" / "2026-08-19" / "run1"
        day.mkdir(parents=True)
        (day / "submits.json").write_text(
            json.dumps([{"stock_code": "600519", "qty": 400, "status": "UNKNOWN_SUBMIT", "order_id": ""}]),
            encoding="utf-8",
        )
        self._patch(monkeypatch, tmp_path, broker)

        # 修复前：report['unknown'] → KeyError，reconcile 命令 100% 崩溃
        rc = cli.cmd_reconcile(argparse.Namespace(trade_date="2026-08-19"))

        assert rc == 1  # UNKNOWN → BLOCKED
        out = capsys.readouterr().out
        assert "未知 1 笔" in out

    def test_reconcile_survives_corrupt_submits_file(self, monkeypatch, tmp_path):
        from paper import cli

        broker = StubBroker()
        day = tmp_path / "paper" / "2026-08-19" / "run1"
        day.mkdir(parents=True)
        # 进程中断留下的半截 JSON
        (day / "submits.json").write_text('[{"stock_code": "600519"', encoding="utf-8")
        self._patch(monkeypatch, tmp_path, broker)

        from paper.reconcile import reconcile_day

        report = reconcile_day(broker, tmp_path, "2026-08-19")

        # 读不出来的 submits 不能静默忽略（会低估敞口），必须 BLOCKED 并列出文件
        assert report["status"] == "BLOCKED"
        assert report["unreadable_submits"]
        assert any("submits 文件损坏" in d for d in report["differences"])


# ------------------------------------------------------------------ config / http

class TestMaxRetryValidation:
    def test_config_rejects_zero_max_retry(self):
        # max_retry=0 会让 mx_http 的 attempts=0 → 返回 None 或断言失败，必须在启动阶段拦下
        with pytest.raises(ValueError, match="max_retry"):
            _config(max_retry=0).validate()

    def test_config_rejects_negative_max_retry(self):
        with pytest.raises(ValueError, match="max_retry"):
            _config(max_retry=-1).validate()

    def test_http_zero_max_retry_still_attempts_once(self, tmp_path):
        """配置层被绕过（直接构造 max_retry=0）时也必须真的发一次请求并返回 MxResult。

        修复前 attempts=0 → range(0) 空转 → last 仍是 None → 触发 assert（-O 下返回 None，
        调用方在 result.ok 上 AttributeError）。
        """
        import requests

        from paper.mx_http import MxHttpClient

        client = MxHttpClient(_config(max_retry=0), artifact_dir=tmp_path)
        calls: list[int] = []

        def _post(*_a, **_kw):
            calls.append(1)
            raise requests.ConnectionError("boom")

        client._session.post = _post  # type: ignore[method-assign]
        result = client.post("/balance", {}, retryable=True)

        assert len(calls) == 1  # 恰好尝试一次，不多不少
        assert result is not None
        assert result.ok is False
        assert result.code == "HTTP_ERROR"
        client.close()


# ----------------------------------------------------------- runner._match_recent_order

class TestMatchRecentOrder:
    def test_does_not_match_opposite_side(self, tmp_path):
        broker = StubBroker()
        runner = _runner(tmp_path, {}, broker)
        runner._preexisting_order_ids = set()
        # orders 里只有一笔同代码同数量的买单；本次是卖单
        broker._orders = [MxOrder(order_id="ORD-BUY", code="600519", side="buy", price=100.0,
                                  qty=400, filled_qty=0, status="2")]
        run = _run_stub()
        intent = Intent(stock_code="600519", intent_type=IntentType.BUY_LIMIT, side="sell",
                        qty=400, limit_price=100.0)

        # 修复前：只比 code+qty → 唯一命中，把卖单记成买单单号（假 SUBMITTED）
        assert runner._match_recent_order(intent, run) is None

    def test_does_not_match_preexisting_order(self, tmp_path):
        broker = StubBroker()
        runner = _runner(tmp_path, {}, broker)
        broker._orders = [MxOrder(order_id="ORD-OLD", code="600519", side="buy", price=100.0,
                                  qty=400, filled_qty=0, status="2")]
        runner._preexisting_order_ids = {"ORD-OLD"}
        run = _run_stub()
        intent = Intent(stock_code="600519", intent_type=IntentType.BUY_LIMIT, side="buy",
                        qty=400, limit_price=100.0)

        assert runner._match_recent_order(intent, run) is None

    def test_does_not_match_order_already_claimed_in_run(self, tmp_path):
        broker = StubBroker()
        runner = _runner(tmp_path, {}, broker)
        broker._orders = [MxOrder(order_id="ORD-1", code="600519", side="buy", price=100.0,
                                  qty=400, filled_qty=0, status="2")]
        runner._preexisting_order_ids = set()
        run = _run_stub()
        run.submits.append({"order_id": "ORD-1", "status": "SUBMITTED"})
        intent = Intent(stock_code="600519", intent_type=IntentType.BUY_LIMIT, side="buy",
                        qty=400, limit_price=100.0)

        assert runner._match_recent_order(intent, run) is None

    def test_matches_same_side_new_order(self, tmp_path):
        broker = StubBroker()
        runner = _runner(tmp_path, {}, broker)
        broker._orders = [MxOrder(order_id="ORD-NEW", code="600519", side="buy", price=100.0,
                                  qty=400, filled_qty=0, status="2")]
        runner._preexisting_order_ids = set()
        run = _run_stub()
        intent = Intent(stock_code="600519", intent_type=IntentType.BUY_LIMIT, side="buy",
                        qty=400, limit_price=100.0)

        assert runner._match_recent_order(intent, run) == "ORD-NEW"


def _run_stub():
    from paper.models import PaperRun

    return PaperRun(run_id="R1", mode="run", signal_date="2026-08-18", trade_date="2026-08-19")


# ------------------------------------------------- preview 跨进程（磁盘加载）行情

class TestPreviewQuotesOnDisk:
    def test_disk_loaded_preview_still_passes_quote_gate(self, tmp_path):
        """换进程按 preview_run_id 加载 preview 时，行情原文必须能恢复。

        修复前 preview.json 不落盘 quotes（只有内存 _quotes），磁盘加载后 _quotes={} →
        每笔都判 QUOTE_NOT_VALID → 整轮 BLOCKED（confirm 的"不重新查行情"契约无法成立）。
        """
        broker = StubBroker()
        cands = [Candidate(stock_code="600519", stock_name="t", exchange_type="SH", source="file")]
        first = _runner(tmp_path, {"600519": _quote()}, broker)
        preview, abort = first.dry_run("2026-08-18", candidates_override=cands)
        assert abort is None
        assert broker.trade_calls == []

        # preview.json 必须含 quotes（含 decide 推导出的涨停价）
        on_disk = json.loads((tmp_path / "paper" / "2026-08-19" / preview.run_id
                              / "preview.json").read_text(encoding="utf-8"))
        assert on_disk["quotes"]["600519"]["limit_up_price"] is not None

        # 全新 runner（无内存 preview）凭 preview_run_id + token 执行
        second = _runner(tmp_path, {}, broker)
        run, abort2 = second.run_confirm(
            "2026-08-18", on_disk["confirm_token"], preview_run_id=preview.run_id
        )

        assert abort2 is None
        assert len(broker.trade_calls) == 1
        assert run.submits[0]["status"] == "SUBMITTED"


# ----------------------------------------------------------------- atomic writes

class TestAtomicWrite:
    def test_atomic_write_replaces_without_leaving_tmp(self, tmp_path):
        from paper.artifacts import atomic_write_text

        target = tmp_path / "submits.json"
        atomic_write_text(target, "[]")
        atomic_write_text(target, '[{"a": 1}]')

        assert json.loads(target.read_text(encoding="utf-8")) == [{"a": 1}]
        assert list(tmp_path.glob("*.tmp")) == []

    def test_atomic_write_keeps_old_content_and_cleans_tmp_on_failure(self, tmp_path):
        from paper.artifacts import atomic_write_text

        target = tmp_path / "submits.json"
        atomic_write_text(target, "[]")

        with pytest.raises(TypeError):
            atomic_write_text(target, None)  # type: ignore[arg-type]

        # 写失败不得破坏已有文件，也不得留下临时文件
        assert json.loads(target.read_text(encoding="utf-8")) == []
        assert list(tmp_path.glob("*.tmp")) == []


def test_paper_config_validate_accepts_default_max_retry():
    assert PaperConfig(api_key="k").max_retry >= 1
    _config().validate()
