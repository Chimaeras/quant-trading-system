"""决策时间与来源边界回归；仅注入假响应，不联网或调用订单接口。"""
from datetime import datetime
from types import SimpleNamespace

import pytest

from paper.candidates import load_from_t1
from paper.clock import CN_TZ
from paper.config import PaperConfig
from paper.models import Candidate
from paper.trading_calendar import TradingCalendarService
from paper.watch.loop import WatchLoop
from paper.watch.models import WatchBar
from paper.watch.signal_adapter import SignalAdapter


def response(monkeypatch, payload):
    monkeypatch.setattr("paper.candidates.requests.get", lambda *args, **kwargs: SimpleNamespace(
        raise_for_status=lambda: None, json=lambda: {"data": payload}))


def test_t1_loader_rejects_date_mismatch_and_missing_snapshot(monkeypatch):
    response(monkeypatch, {"trade_date": "2026-08-17", "candidates": []})
    with pytest.raises(ValueError, match="数据日"):
        load_from_t1("http://unused", "2026-08-18")
    response(monkeypatch, {"found": False, "trade_date": "2026-08-18", "candidates": []})
    with pytest.raises(ValueError, match="快照"):
        load_from_t1("http://unused", "2026-08-18")


def test_t1_loader_does_not_adopt_legacy_probability_as_verified(monkeypatch):
    response(monkeypatch, {"trade_date": "2026-08-18", "status": "OK", "candidates": [{
        "stock_code": "000001", "stock_name": "测试股份", "exchange_type": "SZSE",
        "score": 0.9, "probability": 0.9, "rule_score": 37, "is_probability": True}]})
    candidate = load_from_t1("http://unused", "2026-08-18")[0]
    assert candidate.score == 37
    assert candidate.probability is None
    assert candidate.selection_lineage["trade_date"] == "2026-08-18"


def test_t1_pool_expires_after_its_observation_session(monkeypatch, tmp_path):
    candidate = Candidate("000001", "测试股份", "SZ", signal_date="2026-08-17")
    monkeypatch.setattr("paper.watch.loop.load_watch_universe", lambda *args, **kwargs: ([candidate], "t1", []))
    loop = WatchLoop(PaperConfig(watch_universe_mode="T1"), object(), object(), log_base=tmp_path,
                     calendar=TradingCalendarService(source="test"))
    errors = loop.prepare("2026-08-17", now=datetime(2026, 8, 19, 10))
    assert not loop._candidates
    assert any(e.startswith("T1_INVALID_OBSERVATION_DAY") for e in errors)


def test_signal_carries_bar_time_version_and_bounded_expiry():
    adapter = SignalAdapter()
    start = datetime(2026, 8, 18, 14, 55, tzinfo=CN_TZ)
    end = start.replace(hour=15, minute=0)
    bar = WatchBar("000001", 5, 10, 10, 10, 10, 1000, start, end, closed=True)
    signal = adapter.on_closed_bar("000001", bar, 0)
    assert signal.bar_ts == end.isoformat()
    assert signal.valid_until == end.isoformat()
    assert signal.strategy_version.startswith("intraday_rules_v1:")
    assert signal.holding_horizon == "UNVALIDATED"
    assert "当日收盘" in signal.invalidation_conditions
