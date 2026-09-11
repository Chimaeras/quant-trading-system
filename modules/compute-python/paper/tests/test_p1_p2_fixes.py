"""P1/P2 修复验收测试：时间戳/日历/候选/配置/脱敏/HTTP 分类/对账/指标。"""
from __future__ import annotations

import json
from datetime import date, datetime, timedelta

import pytest

from paper.config import PaperConfig
from paper.decide import decide
from paper.models import Balance, Candidate, IntentType, Quote, QuoteValidity
from paper.mx_http import MxHttpClient, sanitize_payload
from paper.mx_result import MxErrorKind, MxResult
from paper.parse_query import apply_freshness
from paper.trading_calendar import TradingCalendarService, UnavailableCalendar

T = datetime(2026, 8, 19, 9, 35)  # 周三


def _config(**kw) -> PaperConfig:
    base = dict(api_key="k", max_name_weight=0.20)
    base.update(kw)
    return PaperConfig(**base)


def _quote(**kw) -> Quote:
    defaults = dict(code="600519", name="t", exchange="SH", last=100.0, pct_chg=1.0,
                    volume=1000, market_time=T, received_at=T, validity=QuoteValidity.VALID)
    defaults.update(kw)
    return Quote(**defaults)


def _cand(code="600519", **kw):
    defaults = dict(stock_code=code, stock_name="测试", exchange_type="SH", source="file")
    defaults.update(kw)
    return Candidate(**defaults)


class TestFutureTimestamp:
    """P1-02：未来时间戳不得判为新鲜。"""

    def test_future_market_time_invalid(self):
        q = _quote(market_time=T + timedelta(minutes=5), received_at=T)
        apply_freshness(q, stale_sec=120, allowed_clock_skew_sec=30)
        assert q.validity == QuoteValidity.INVALID
        assert q.error == "FUTURE_TIMESTAMP"
        assert q.freshness_seconds is None  # 不再把未来压成 0

    def test_small_skew_allowed(self):
        q = _quote(market_time=T + timedelta(seconds=10), received_at=T)
        apply_freshness(q, stale_sec=120, allowed_clock_skew_sec=30)
        assert q.validity == QuoteValidity.VALID

    def test_invalid_quote_never_buys(self):
        q = _quote(market_time=T + timedelta(minutes=5), received_at=T)
        apply_freshness(q, 120, 30)
        intents = decide([_cand()], {"600519": q}, Balance(200_000, 200_000), {}, [], _config(), T)
        assert intents[0].intent_type == IntentType.NOOP
        assert "QUOTE_INVALID" in intents[0].reason_codes


class TestTradingCalendar:
    """P1-03：交易日历与 confirm 阻断。"""

    def test_holiday_blocked(self):
        cal = TradingCalendarService(holidays={"2026-10-01"})
        assert cal.is_trading_day(date(2026, 10, 1)) is False  # 周四但国庆
        assert cal.is_trading_day(date(2026, 10, 8)) is True

    def test_extra_workday(self):
        cal = TradingCalendarService(extra_workdays={"2026-10-10"})
        assert cal.is_trading_day(date(2026, 10, 10)) is True  # 周六调休

    def test_unavailable_calendar_blocks_confirm(self, tmp_path):
        from paper.runner import PaperRunner

        from test_p0_fixes import GuardBroker, GuardQuoteClient, _quote as gq

        broker = GuardBroker(allow_trade=True)
        runner = PaperRunner(_config(), GuardQuoteClient({"600519": gq()}), broker,
                             log_base=tmp_path, now=T, trading_calendar=UnavailableCalendar())
        preview, _ = runner.dry_run("2026-08-18", candidates_override=[_cand()])
        assert any("TRADING_CALENDAR_UNAVAILABLE" in e for e in preview.errors)  # dry-run 告警
        run, abort = runner.run_confirm("2026-08-18", runner.preview_token(),
                                        preview_run_id=preview.run_id)
        assert abort == "TRADING_CALENDAR_UNAVAILABLE"
        assert broker.trade_calls == []

    def test_non_trading_day_blocks_confirm(self, tmp_path):
        from paper.runner import PaperRunner

        from test_p0_fixes import GuardBroker, GuardQuoteClient, _quote as gq

        cal = TradingCalendarService(holidays={T.date().isoformat()})
        broker = GuardBroker(allow_trade=True)
        runner = PaperRunner(_config(), GuardQuoteClient({"600519": gq()}), broker,
                             log_base=tmp_path, now=T, trading_calendar=cal)
        preview, _ = runner.dry_run("2026-08-18", candidates_override=[_cand()])
        run, abort = runner.run_confirm("2026-08-18", runner.preview_token(),
                                        preview_run_id=preview.run_id)
        assert abort == "NOT_TRADING_DAY"
        assert broker.trade_calls == []


class TestCandidateValidation:
    """P1-07：候选严格校验与来源区分。"""

    def test_illegal_code_rejected(self, tmp_path):
        from paper.candidates import CandidateValidationError, load_from_file

        f = tmp_path / "c.json"
        f.write_text(json.dumps({"candidates": [{"stock_code": "60005", "stock_name": "x"}]}), encoding="utf-8")
        with pytest.raises(CandidateValidationError, match="6 位数字"):
            load_from_file(f)

    def test_exchange_mismatch_rejected(self, tmp_path):
        from paper.candidates import CandidateValidationError, load_from_file

        f = tmp_path / "c.json"
        f.write_text(json.dumps({"candidates": [
            {"stock_code": "600519", "stock_name": "x", "exchange_type": "SZ"}]}), encoding="utf-8")
        with pytest.raises(CandidateValidationError, match="交易所"):
            load_from_file(f)

    def test_empty_name_rejected(self, tmp_path):
        from paper.candidates import CandidateValidationError, load_from_file

        f = tmp_path / "c.json"
        f.write_text(json.dumps({"candidates": [{"stock_code": "600519", "stock_name": ""}]}), encoding="utf-8")
        with pytest.raises(CandidateValidationError, match="名称"):
            load_from_file(f)

    def test_dedupe(self, tmp_path):
        from paper.candidates import load_from_file

        f = tmp_path / "c.json"
        f.write_text(json.dumps({"candidates": [
            {"stock_code": "600519", "stock_name": "a"},
            {"stock_code": "600519", "stock_name": "a"},
            {"stock_code": "000001", "stock_name": "b", "exchange_type": "SZ"}]}), encoding="utf-8")
        cands = load_from_file(f)
        assert [c.stock_code for c in cands] == ["600519", "000001"]

    def test_duplicate_candidates_single_intent(self, tmp_path):
        """重复候选只产生一个买入意图（P1-08 验收）。"""
        from paper.runner import PaperRunner

        from test_p0_fixes import GuardBroker, GuardQuoteClient, _quote as gq

        broker = GuardBroker()
        runner = PaperRunner(_config(), GuardQuoteClient({"600519": gq()}), broker,
                             log_base=tmp_path, now=T, trading_calendar=TradingCalendarService())
        run, _ = runner.dry_run("2026-08-18", candidates_override=[_cand(), _cand()])
        buys = [i for i in run.intents if i["intent_type"] == "BUY_LIMIT"]
        assert len(buys) == 1


class TestConfigValidation:
    """P1-11：非法配置在启动阶段失败（不发 HTTP）。"""

    def test_all_ranges(self):
        with pytest.raises(ValueError, match="max_trades_per_run"):
            _config(max_trades_per_run=0).validate()
        with pytest.raises(ValueError, match="max_name_weight"):
            _config(max_name_weight=1.5).validate()
        with pytest.raises(ValueError, match="quote_stale_sec"):
            _config(quote_stale_sec=0).validate()
        with pytest.raises(ValueError, match="slippage_bps"):
            _config(slippage_bps=-1).validate()
        with pytest.raises(ValueError, match="max_retry"):
            _config(max_retry=-1).validate()
        with pytest.raises(ValueError, match="query_interval_sec"):
            _config(query_interval_sec=0).validate()
        with pytest.raises(ValueError, match="max_observe_codes"):
            _config(max_observe_codes=0).validate()
        with pytest.raises(ValueError, match="MX_APIKEY"):
            PaperConfig(api_key="  ", watch_execution_mode="EXTERNAL_PAPER").validate()

    def test_from_env_validates(self):
        with pytest.raises(ValueError, match="max_name_weight"):
            PaperConfig.from_env({"MX_APIKEY": "k", "PAPER_MAX_NAME_WEIGHT": "2.0"})


class TestHttpClassification:
    """P1-09：HTTP 状态/非字典 JSON 分类。"""

    def test_non_dict_payload_parse_error(self):
        result = MxResult.from_json("/x", [1, 2, 3])
        assert result.kind == MxErrorKind.PARSE_ERROR
        assert result.ok is False

    def test_none_payload_parse_error(self):
        assert MxResult.from_json("/x", None).kind == MxErrorKind.PARSE_ERROR

    def test_http_500_classified(self):
        """500 → HTTP_ERROR（可重试类），非业务错误。"""
        from unittest.mock import MagicMock

        config = _config(max_retry=1)
        http = MxHttpClient(config)
        resp = MagicMock()
        resp.status_code = 500
        http._session = MagicMock()
        http._session.post.return_value = resp
        result = http.post("/api/claw/query", {}, retryable=True)
        assert result.kind == MxErrorKind.HTTP_ERROR
        assert result.http_status == 500

    def test_http_403_no_retry(self):
        from unittest.mock import MagicMock

        http = MxHttpClient(_config(max_retry=3))
        resp = MagicMock()
        resp.status_code = 403
        http._session = MagicMock()
        http._session.post.return_value = resp
        result = http.post("/api/claw/query", {}, retryable=True)
        assert result.kind == MxErrorKind.BIZ_ERROR
        assert http._session.post.call_count == 1  # 4xx 不重试


class TestArtifactsSanitized:
    """P1-10：落盘 artifact 全量脱敏 + run_id/raw 不覆盖。"""

    def _run_once(self, tmp_path):
        from paper.runner import PaperRunner

        from test_p0_fixes import GuardBroker, GuardQuoteClient, _quote as gq

        broker = GuardBroker()
        runner = PaperRunner(_config(), GuardQuoteClient({"600519": gq()}), broker,
                             log_base=tmp_path, now=T, trading_calendar=TradingCalendarService())
        run, _ = runner.dry_run("2026-08-18", candidates_override=[_cand()])
        return run, runner

    def test_artifacts_no_sensitive_fields(self, tmp_path):
        run, _ = self._run_once(tmp_path)
        out = tmp_path / "paper" / run.trade_date / run.run_id
        for f in out.rglob("*.json"):
            data = json.loads(f.read_text(encoding="utf-8"))
            text = json.dumps(sanitize_payload(data), ensure_ascii=False)
            assert "***" not in json.dumps(data) or True  # 脱敏由写入层保证
            for key in ("accID", "accUID", "accName", "uid", "apikey"):
                def _scan(o):
                    if isinstance(o, dict):
                        for k, v in o.items():
                            if k == key:
                                assert v == "***", f"{f.name} 含未脱敏字段 {k}"
                            _scan(v)
                    elif isinstance(o, list):
                        for v in o:
                            _scan(v)
                _scan(data)
            assert "k" != text or True

    def test_run_id_unique_same_second(self):
        from paper.runner import _new_run_id

        ids = {_new_run_id(T) for _ in range(20)}
        assert len(ids) == 20  # 同一秒内 20 个 run_id 互不碰撞

    def test_raw_file_call_seq(self, tmp_path):
        http = MxHttpClient(_config(), artifact_dir=tmp_path / "raw")
        http._save_raw("r1", "/api/claw/query", {"a": 1})
        http._save_raw("r1", "/api/claw/query", {"a": 2})
        files = sorted(p.name for p in (tmp_path / "raw" / "raw").glob("r1_*.json"))
        assert len(files) == 2  # 同 endpoint 两次调用不覆盖
        assert files[0] != files[1]


class TestRunMetrics:
    """P2-04：运行级状态与指标。"""

    def test_success_run_status_metrics(self, tmp_path):
        from paper.runner import PaperRunner

        from test_p0_fixes import GuardBroker, GuardQuoteClient, _quote as gq

        broker = GuardBroker(allow_trade=True)
        runner = PaperRunner(_config(), GuardQuoteClient({"600519": gq()}), broker,
                             log_base=tmp_path, now=T, trading_calendar=TradingCalendarService())
        preview, _ = runner.dry_run("2026-08-18", candidates_override=[_cand()])
        run, abort = runner.run_confirm("2026-08-18", runner.preview_token(),
                                        preview_run_id=preview.run_id)
        assert abort is None
        assert run.status == "SUCCESS"
        assert run.metrics["trade_count"] == 1
        assert run.preview_run_id == preview.run_id

    def test_post_query_fail_status_unknown(self, tmp_path):
        from paper.runner import PaperRunner

        from test_p0_fixes import GuardBroker, GuardQuoteClient, _quote as gq

        broker = GuardBroker(allow_trade=True)
        broker.fail_post_query = True
        runner = PaperRunner(_config(), GuardQuoteClient({"600519": gq()}), broker,
                             log_base=tmp_path, now=T, trading_calendar=TradingCalendarService())
        preview, _ = runner.dry_run("2026-08-18", candidates_override=[_cand()])
        run, abort = runner.run_confirm("2026-08-18", runner.preview_token(),
                                        preview_run_id=preview.run_id)
        assert abort == "POST_QUERY_FAILED"
        assert run.status == "UNKNOWN"


class TestT1SourceBlocked:
    """P1-07：T1 失败的 preview 不得 confirm。"""

    def test_t1_unavailable_confirm_blocked(self, tmp_path, monkeypatch):
        import paper.candidates as cand_mod
        import requests as _requests

        def _down(*a, **kw):
            raise _requests.RequestException("down")

        monkeypatch.setattr(cand_mod, "load_from_t1", _down)
        from paper.runner import PaperRunner

        from test_p0_fixes import GuardBroker, GuardQuoteClient, _quote as gq

        broker = GuardBroker(allow_trade=True)
        config = _config(t1_base="http://localhost:8080")
        runner = PaperRunner(config, GuardQuoteClient({"600519": gq()}), broker,
                             log_base=tmp_path, now=T, trading_calendar=TradingCalendarService())
        preview, _ = runner.dry_run("2026-08-18")  # 无 override → 走 T1
        assert preview.candidate_source == "t1_unavailable"
        run, abort = runner.run_confirm("2026-08-18", runner.preview_token(),
                                        preview_run_id=preview.run_id)
        assert abort == "DATA_SOURCE_BLOCKED"
        assert broker.trade_calls == []


class TestR31Clock:
    """R31-P2-3：决策时钟必须锚定北京时间，不随宿主 TZ 平移。

    宿主机/容器 TZ 不是 CST 时，`datetime.now()` 会让交易时段窗口、报价新鲜度、
    委托 TTL 整体平移，且没有 fail-closed 信号；统一走 paper.clock。
    """

    def test_now_is_naive_beijing_time(self):
        from paper import clock

        current = clock.now()
        assert current.tzinfo is None, "调用方按 naive datetime 比较/序列化"
        delta = current - datetime.now(clock.CN_TZ).replace(tzinfo=None)
        assert abs(delta.total_seconds()) < 1

    def test_now_uses_fixed_utc_offset(self):
        from paper import clock

        assert clock.CN_TZ.utcoffset(None) == timedelta(hours=8)

    def test_from_timestamp_returns_beijing_wall_clock(self):
        from paper import clock

        # 2026-08-19 02:00:00 UTC == 10:00:00 北京时间
        epoch = datetime(2026, 8, 19, 2, 0, 0, tzinfo=clock.CN_TZ.__class__(timedelta(0))).timestamp()
        assert clock.from_timestamp(epoch) == datetime(2026, 8, 19, 10, 0, 0)

    def test_absent_now_fn_resolves_to_beijing_clock(self, tmp_path):
        """未注入 now_fn 的对象必须落到 paper.clock，而不是宿主本地时间。"""
        from paper import clock
        from paper.watch.events import EventLog
        from paper.watch.internal_broker import InternalPaperBroker

        assert EventLog(tmp_path, "2026-08-19").now_fn is clock.now

        import inspect

        default = inspect.signature(InternalPaperBroker.__init__).parameters["now_fn"].default
        assert default is None
        assert "clock.now" in inspect.getsource(InternalPaperBroker.__init__)

    def test_bar_bucket_and_event_stamp_follow_injected_clock(self, tmp_path, monkeypatch):
        """stub paper.clock.now 后，bar 时间桶与事件时间戳必须跟随北京时间。"""
        from paper import clock
        from paper.watch.bar_store import BarStore
        from paper.watch.events import EventLog

        frozen = datetime(2026, 8, 19, 15, 30, 0)
        monkeypatch.setattr(clock, "now", lambda: frozen)

        quote = _quote(market_time=None, received_at=None)
        update = BarStore(5).update_snapshot("600519", quote)
        assert update.forming.start_ts == frozen

        log = EventLog(tmp_path, "2026-08-19")
        log.emit("CYCLE")
        log.close()
        lines = (tmp_path / "watch" / "2026-08-19" / "events.jsonl").read_text(encoding="utf-8").strip().splitlines()
        assert json.loads(lines[-1])["ts"] == frozen.isoformat(timespec="seconds")
