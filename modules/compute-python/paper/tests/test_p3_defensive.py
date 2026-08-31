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
