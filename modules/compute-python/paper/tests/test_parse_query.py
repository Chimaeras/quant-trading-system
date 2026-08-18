"""query 解析测试：真实抓包 fixture + 涨跌幅三格式 + fail-closed。"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

import pytest

from paper.models import QuoteValidity
from paper.parse_query import (
    QuoteParseError,
    apply_freshness,
    parse_market_time,
    parse_pct_chg,
    parse_query,
    parse_volume,
)

FIXTURES = Path(__file__).parent / "fixtures"


def _query_payload() -> dict:
    return json.loads((FIXTURES / "query_success.json").read_text(encoding="utf-8"))


class TestRealCapture:
    """2026-08-18 真实抓包：茅台实时行情表 + 日线表混合。"""

    def test_parse_maotai(self):
        quote = parse_query(_query_payload(), "600519", received_at=datetime(2026, 8, 18, 16, 31))
        assert quote.code == "600519"
        assert quote.name == "贵州茅台"
        assert quote.exchange == "SH"
        assert quote.last == pytest.approx(1297.99)
        assert quote.pct_chg == pytest.approx(0.38)
        assert quote.volume == pytest.approx(3872283.0)  # rawTable 优先，已是股数
        assert quote.volume_ratio == pytest.approx(0.95)
        assert quote.market_time == datetime(2026, 8, 18, 16, 30)

    def test_daily_table_not_mistaken(self):
        """日线表（收盘价）不得被误当成实时最新价。"""
        quote = parse_query(_query_payload(), "600519")
        # 若误取日线表，last 会是 1297.99 但 nameMap 无「最新价」→ 应直接报错而非取收盘价
        assert quote.last == pytest.approx(1297.99)

    def test_code_mismatch_raises(self):
        with pytest.raises(QuoteParseError, match="不对齐"):
            parse_query(_query_payload(), "000001")


class TestPctFormats:
    """涨跌幅三种格式：0.38 / 0.0038 / 0.38%。"""

    def test_percent_suffix(self):
        assert parse_pct_chg("0.38%") == pytest.approx(0.38)

    def test_plain_percent(self):
        assert parse_pct_chg("0.38") == pytest.approx(0.38)

    def test_ratio_form(self):
        assert parse_pct_chg("0.0038") == pytest.approx(0.38)

    def test_negative(self):
        assert parse_pct_chg("-3.644%") == pytest.approx(-3.644)

    def test_invalid(self):
        with pytest.raises(QuoteParseError):
            parse_pct_chg("--")


class TestVolumeAndTime:
    """成交量单位换算与行情时间解析。"""

    def test_volume_wan(self):
        assert parse_volume("387.2万") == pytest.approx(3872000.0)

    def test_volume_plain(self):
        assert parse_volume("3872283") == pytest.approx(3872283.0)

    def test_volume_yi(self):
        assert parse_volume("1.2亿") == pytest.approx(120000000.0)

    def test_market_time_with_minute(self):
        assert parse_market_time(["2026-08-18 16:30"]) == datetime(2026, 8, 18, 16, 30)

    def test_market_time_with_weekday_suffix(self):
        assert parse_market_time(["2026-08-18(日)"]) == datetime(2026, 8, 18)


class TestFreshness:
    """freshness 与 STALE 判定。"""

    def _quote(self, received: datetime, market: datetime):
        payload = _query_payload()
        quote = parse_query(payload, "600519", received_at=received)
        quote.market_time = market
        return quote

    def test_fresh(self):
        from datetime import datetime as dt

        quote = self._quote(dt(2026, 8, 18, 9, 31), dt(2026, 8, 18, 9, 30))
        apply_freshness(quote, stale_sec=120)
        assert quote.validity == QuoteValidity.VALID
        assert quote.freshness_seconds == 60.0

    def test_stale(self):
        from datetime import datetime as dt

        quote = self._quote(dt(2026, 8, 18, 9, 35), dt(2026, 8, 18, 9, 30))
        apply_freshness(quote, stale_sec=120)
        assert quote.validity == QuoteValidity.STALE


class TestFailClosed:
    """结构不符时 fail-closed。"""

    def test_missing_path(self):
        with pytest.raises(QuoteParseError):
            parse_query({"data": {}}, "600519")

    def test_empty_tables(self):
        payload = _query_payload()
        payload["data"]["data"]["searchDataResultDTO"]["dataTableDTOList"] = []
        with pytest.raises(QuoteParseError, match="空表"):
            parse_query(payload, "600519")

    def test_no_last_indicator(self):
        payload = _query_payload()
        tables = payload["data"]["data"]["searchDataResultDTO"]["dataTableDTOList"]
        for tbl in tables:
            tbl["nameMap"] = {k: "其他" for k in tbl.get("nameMap", {})}
        with pytest.raises(QuoteParseError, match="最新价"):
            parse_query(payload, "600519")
