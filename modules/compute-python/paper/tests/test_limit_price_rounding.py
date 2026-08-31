"""P2-6 回归：涨跌停价/限价取整必须为 A 股『四舍五入』（半价上舍入），而非银行家舍入。

Python 内置 round 使用银行家舍入，在精确 .005 边界会舍到偶数（round(2.675,2)→2.67），
与交易所约定不一致；P2-6 修复为 Decimal ROUND_HALF_UP（round(2.675,2)→2.68）。

覆盖两处接入点：
- paper/marketdata/rules.py apply_rule_price_limits（watch 运行时 security_rule 路径）
- paper/decide.py _round_half_up / derive_limit_up_price（研判 derive 路径）
"""
from paper.decide import _round_half_up
from paper.marketdata.rules import SecurityRule, apply_rule_price_limits
from paper.models import Quote


def _rule(limit_rate: float = 0.10, price_decimals: int | None = 2) -> SecurityRule:
    return SecurityRule.from_dict({
        "stock_code": "000001",
        "exchange_type": "SZSE",
        "board": "MAIN",
        "status": "NORMAL",
        "is_st": False,
        "tick_size": 0.01,
        "price_decimals": price_decimals,
        "limit_rate": limit_rate,
        "buy_lot": 100,
        "settlement": "T+1",
        "rule_version": "v2",
        "source": "snapshot",
        "reliability": "official",
        "effective_date": "2026-08-01",
    })


def test_round_half_up_is_four_she_wu_ru_not_bankers():
    # 银行家舍入：round(2.675,2)=2.67、round(1.265,2)=1.26、round(11.165,2)=11.16
    # 四舍五入（half up）：2.68 / 1.27 / 11.17
    assert _round_half_up(2.675, 2) == 2.68
    assert _round_half_up(1.265, 2) == 1.27
    assert _round_half_up(11.165, 2) == 11.17


def test_apply_rule_price_limits_set_both_limits_and_sources():
    # last=11.0, pct_chg=10.0 → prev_close=10.0；涨停=11.00，跌停=9.00（干净值，非边界）
    q = Quote(code="000001", name="平安银行", exchange="SZSE", last=11.0, pct_chg=10.0)
    apply_rule_price_limits(q, _rule())
    assert q.limit_up_price == 11.0
    assert q.limit_down_price == 9.0
    assert q.limit_up_source == "security_rule:v2:snapshot"
    assert q.limit_down_source == "security_rule:v2:snapshot"


def test_apply_rule_price_limits_skip_when_inputs_unreliable():
    # pct_chg 缺失 → 不补（由上游风控闸 LIMIT_DATA_UNAVAILABLE 阻断）
    q = Quote(code="000001", name="平安银行", exchange="SZSE", last=11.0, pct_chg=None)
    apply_rule_price_limits(q, _rule())
    assert q.limit_up_price is None
    assert q.limit_down_price is None
