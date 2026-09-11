"""P3 防御性修复回归：行情浮点 inf 拦截、热门股票池逐行隔离。"""
from __future__ import annotations

import math

from paper.models import Quote
from paper.quote_client import _float_field
from paper.watch.universe import load_hot_stock_universe


def test_float_field_rejects_inf_and_nan():
    # inf 在旧实现下能通过 (number<0 and not allow_negative) 检查（inf>=0）而漏网；
    # 修复后 not math.isfinite 一并拦截 NaN 与 ±inf。
    assert _float_field(float("inf")) is None
    assert _float_field(float("-inf")) is None
    assert _float_field(float("nan")) is None
    assert _float_field(float("nan"), allow_negative=True) is None
    assert _float_field(12.5) == 12.5
    assert _float_field("12.5", scale=2.0) == 25.0


def test_float_field_allow_negative_still_rejects_inf():
    # allow_negative=True 只放开负值符号，不放开非有限数。
    assert _float_field(-3.3, allow_negative=True) == -3.3
    assert _float_field(float("-inf"), allow_negative=True) is None


def _mock_hot_universe(monkeypatch, items, *, base_url="http://mock", token=""):
    import paper.watch.universe as universe_mod

    class FakeResp:
        def raise_for_status(self):
            return None

        def json(self):
            return {"data": {"items": items}}

    monkeypatch.setattr(universe_mod.requests, "get", lambda *a, **k: FakeResp())
    return load_hot_stock_universe(base_url, "2026-08-18", token, max_codes=50)


def test_load_hot_stock_universe_skips_invalid_row_without_aborting(monkeypatch):
    # 单行坏数据（缺失代码 / 非法代码 / 非 dict）只丢弃该行，不拖垮整批。
    items = [
        None,
        {"code": "bad-code", "exchange_type": "SH"},       # 非法代码 → _validated_code 抛错
        {"code": "600519", "exchange_type": "SH", "stock_name": "贵州茅台"},
        {"stock_code": "000001", "exchange_type": "SZSE", "name": "平安银行"},
    ]
    out = _mock_hot_universe(monkeypatch, items)
    codes = {c.stock_code for c in out}
    assert codes == {"600519", "000001"}
    assert all(c.source == "hot_stocks" for c in out)


def test_parse_price_rejects_inf_and_nan():
    # R31-P3-2：inf 能穿过 `value <= 0`（inf<=0 为假），把涨停价笼子变成 fail-open。
    import pytest

    from paper.parse_query import QuoteParseError, parse_price

    assert parse_price("10.25") == 10.25
    for raw in ("inf", "-inf", "Infinity", "nan", "NaN"):
        with pytest.raises(QuoteParseError):
            parse_price(raw)
    with pytest.raises(QuoteParseError):
        parse_price("0")


def test_parse_volume_rejects_inf_and_nan():
    import pytest

    from paper.parse_query import QuoteParseError, parse_volume

    assert parse_volume("387.2万") == 3872000.0
    for raw in ("inf", "1e400", "nan", "NaN"):
        with pytest.raises(QuoteParseError):
            parse_volume(raw)


def test_parse_pct_chg_rejects_inf_and_nan():
    import pytest

    from paper.parse_query import QuoteParseError, parse_pct_chg

    assert parse_pct_chg("0.0038") == pytest.approx(0.38)
    for raw in ("inf", "-inf", "NaN", "Infinity"):
        with pytest.raises(QuoteParseError):
            parse_pct_chg(raw)


def _query_payload(*, last="10.00", limit_up=None, volume="1000"):
    raw = {"ZXJ": [last], "CJL": [volume]}
    name_map = {"ZXJ": "最新价", "CJL": "成交量"}
    if limit_up is not None:
        raw["ZTJ"] = [limit_up]
        name_map["ZTJ"] = "涨停价"
    return {
        "data": {
            "data": {
                "searchDataResultDTO": {
                    "dataTableDTOList": [
                        {
                            "entityTagDTO": {"secuCode": "600519", "fullName": "贵州茅台", "marketChar": ".SH"},
                            "nameMap": name_map,
                            "headName": ["2026-08-19 10:00"],
                            "table": {key: list(value) for key, value in raw.items()},
                            "rawTable": raw,
                        }
                    ]
                }
            }
        }
    }


def test_query_parse_marks_inf_limit_price_as_parse_error():
    """inf 涨停价不得被当成有效风控输入（旧实现会给 source 打上"已解析"标签）。"""
    from paper.parse_query import parse_query

    quote = parse_query(_query_payload(limit_up="inf"), "600519")
    assert quote.limit_up_price is None
    assert quote.limit_up_source.endswith("PARSE_ERROR")


def test_query_parse_rejects_inf_last_price():
    import pytest

    from paper.parse_query import QuoteParseError, parse_query

    with pytest.raises(QuoteParseError):
        parse_query(_query_payload(last="inf"), "600519")


def test_query_parse_ignores_inf_volume_ratio():
    payload = _query_payload()
    target = payload["data"]["data"]["searchDataResultDTO"]["dataTableDTOList"][0]
    target["rawTable"]["LB"] = ["inf"]
    target["table"]["LB"] = ["inf"]
    target["nameMap"]["LB"] = "量比"

    from paper.parse_query import parse_query

    quote = parse_query(payload, "600519")
    assert quote.volume_ratio is None
