"""R4-02 / R5-02 / R6-02 回归测试。

这三项由 Codex 于 2026-10-13 独立验证确认存在（详见 quant-accuracy-issues.md）。
每个用例都锚定一个**修复前会失败**的具体断言，以防回归。
"""
from datetime import date, datetime, timedelta
from pathlib import Path

import pytest

from paper.config import PaperConfig
from paper.models import (
    Balance,
    Candidate,
    Intent,
    IntentType,
    Quote,
    QuoteValidity,
    is_bse,
)
from paper.risk import check_intent, check_watch_intent, near_limit_up_threshold_pct
from paper.trading_calendar import TradingCalendarService, load_calendar_from_file
from paper.watch.kline_client import _parse_eastmoney_klines
from paper.watch.bar_store import BarStore
from paper.watch.models import WatchBar

_CALENDAR_PATH = Path(__file__).resolve().parents[1] / "trading-calendar.2026.json"
_T = datetime(2026, 10, 13, 10, 0)


def _quote(code: str, exchange: str, pct_chg: float, limit_pct: float) -> Quote:
    """构造报价：last=20，涨停价按板块涨停幅度推导。"""
    return Quote(
        code=code, name="测试", exchange=exchange, last=20.0, pct_chg=pct_chg,
        volume=1000, market_time=_T, received_at=_T, validity=QuoteValidity.VALID,
        limit_up_price=20.0 * (1 + limit_pct / 100.0), limit_up_source="test",
    )


def _run_buy(quote: Quote, *, qty: int = 100):
    """账户充裕、无持仓，确保只有「闸 7b 近涨停」可能拦截。"""
    config = PaperConfig(api_key="k", max_name_weight=0.20, max_trades_per_run=5)
    balance = Balance(total_assets=1_000_000, avail_balance=1_000_000)
    intent = Intent(
        stock_code=quote.code, intent_type=IntentType.BUY_LIMIT, qty=qty,
        limit_price=quote.last, candidate_source="file",
    )
    return check_intent(
        intent, quote, balance, [], config, 0, {quote.code},
        confirmed=True, positions_qty={},
    )


# --------------------------------------------------------------------------
# R4-02：V1 闸 check_intent 的近涨停阈值必须按板块推导，不得硬编码 9.0%
# --------------------------------------------------------------------------

def test_near_limit_threshold_is_derived_per_board():
    """前置：阈值来源本身按板块（主板 9 / 两创 19 / 北交所 29）。"""
    assert near_limit_up_threshold_pct("600519", "SH") == pytest.approx(9.0)
    assert near_limit_up_threshold_pct("300530", "SZ") == pytest.approx(19.0)
    assert near_limit_up_threshold_pct("688022", "SH") == pytest.approx(19.0)
    assert near_limit_up_threshold_pct("920627", "BJ") == pytest.approx(29.0)


@pytest.mark.parametrize(
    "code,exchange,limit_pct,pct_chg,expect_near_limit_up",
    [
        # 主板（10%）：阈值 9.0
        ("600519", "SH", 10, 5.0, False),
        ("600519", "SH", 10, 9.5, True),
        # 创业板（20%）：阈值 19.0 —— +12% 距涨停尚有 8 个点，不得判为近涨停
        ("300530", "SZ", 20, 12.0, False),
        # 科创板（20%）：同上
        ("688022", "SH", 20, 12.0, False),
        # 北交所（30%）：阈值 29.0 —— +15% 距涨停 15 个点，修复前被 9.0 硬编码误拒
        ("920627", "BJ", 30, 15.0, False),
        # 北交所真正接近涨停时仍要拦住
        ("920627", "BJ", 30, 29.5, True),
    ],
)
def test_check_intent_uses_per_board_near_limit_threshold(
    code, exchange, limit_pct, pct_chg, expect_near_limit_up
):
    """R4-02：同一报价下 V1 闸不得再按固定 9.0% 判近涨停。

    修复前 `check_intent` 为 `if (quote.pct_chg or 0) >= 9.0`，于是北交所
    +15% 的正常标的被判 NEAR_LIMIT_UP，而 watch 闸（按板块为 29.0）判 PASS，
    同一输入两闸结论相反。
    """
    out = _run_buy(_quote(code, exchange, pct_chg, limit_pct))
    if expect_near_limit_up:
        assert out.risk_reject_reason == "NEAR_LIMIT_UP"
    else:
        assert out.risk_reject_reason != "NEAR_LIMIT_UP", (
            f"{code} 涨幅 {pct_chg}% / 板块涨停 {limit_pct}% 不应判为近涨停"
        )


def test_bse_buy_passes_v1_gate_at_fifteen_percent():
    """北交所 +15% 的具体回归点（Codex 实测的相反结论）。"""
    out = _run_buy(_quote("920627", "BJ", 15.0, 30))
    assert out.risk_reject_reason != "NEAR_LIMIT_UP"
    assert out.reason_codes == []


# --------------------------------------------------------------------------
# R5-02：V1 对不支持意图类型的拒绝必须写入原因码（可审计）
# --------------------------------------------------------------------------

def test_check_intent_records_reason_code_for_unsupported_intent_type():
    """修复前 SELL_LIMIT 被 REJECT 但 reject_reason=''、reason_codes=[]。"""
    config = PaperConfig(api_key="k")
    balance = Balance(total_assets=1_000_000, avail_balance=1_000_000)
    quote = _quote("600519", "SH", 0.0, 10)
    intent = Intent(
        stock_code="600519", intent_type=IntentType.SELL_LIMIT, qty=100,
        limit_price=20.0, candidate_source="file",
    )

    out = check_intent(
        intent, quote, balance, [], config, 0, {"600519"},
        confirmed=True, positions_qty={},
    )

    assert out.risk_status.value == "REJECT"
    assert out.risk_reject_reason == "UNSUPPORTED_INTENT_TYPE_IN_V1"
    assert "UNSUPPORTED_INTENT_TYPE_IN_V1" in out.reason_codes


def test_check_intent_still_passes_noop_without_reason():
    """对照：NOOP 仍为 PASS 且不写原因码（不得把 NOOP 也当成不支持类型）。"""
    config = PaperConfig(api_key="k")
    balance = Balance(total_assets=1_000_000, avail_balance=1_000_000)
    quote = _quote("600519", "SH", 0.0, 10)
    intent = Intent(
        stock_code="600519", intent_type=IntentType.NOOP, qty=0,
        limit_price=None, candidate_source="file",
    )

    out = check_intent(
        intent, quote, balance, [], config, 0, {"600519"},
        confirmed=True, positions_qty={},
    )

    assert out.risk_status.value == "PASS"
    assert out.risk_reject_reason == ""
    assert out.reason_codes == []


# --------------------------------------------------------------------------
# R6-02：日历覆盖区间契约 —— 区间外必须 fail-closed
# --------------------------------------------------------------------------

def test_calendar_declared_coverage_fails_closed_outside_range():
    """声明覆盖区间后，区间外日期一律非交易日。

    修复前 `is_trading_day` 无条件回退 `weekday() < 5`，于是超出假日表范围的
    年份（如 2027）全部工作日都被放行，法定节假日失效。
    """
    cal = TradingCalendarService(
        holidays={"2026-10-01"},
        coverage_from="2026-01-01",
        coverage_to="2026-12-31",
    )

    assert cal.coverage_window() == (date(2026, 1, 1), date(2026, 12, 31))
    # 区间内
    assert cal.is_trading_day(date(2026, 10, 13)) is True   # 周二
    assert cal.is_trading_day(date(2026, 10, 1)) is False   # 区间内法定节假日
    assert cal.covers(date(2026, 10, 13)) is True
    # 区间外：fail-closed
    assert cal.covers(date(2027, 1, 1)) is False
    assert cal.is_trading_day(date(2027, 1, 1)) is False
    assert cal.available_for(date(2027, 1, 1)) is False
    assert cal.available_for(date(2026, 10, 13)) is True


def test_calendar_without_declared_coverage_keeps_legacy_behaviour():
    """未声明覆盖区间时行为不变——既有调用方与测试依赖周一至周五回退。"""
    cal = TradingCalendarService()
    assert cal.coverage_window() is None
    assert cal.covers(date(2027, 1, 1)) is True
    assert cal.available_for(date(2027, 1, 1)) is True


def test_repo_calendar_file_declares_coverage_and_fails_closed():
    """仓库日历文件必须声明覆盖区间，否则本修复形同未启用。"""
    cal = load_calendar_from_file(_CALENDAR_PATH)
    assert cal.coverage_window() == (date(2026, 1, 1), date(2026, 12, 31))
    assert cal.is_trading_day(date(2026, 10, 13)) is True
    assert cal.is_trading_day(date(2027, 1, 1)) is False


# --------------------------------------------------------------------------
# R1-01：东财 K 线标签是 bar 的「结束」时刻
# --------------------------------------------------------------------------

def _em_payload(pairs):
    rows = [f"2026-10-13 {label},10,{close},10,10,1,0,0,0,0,0" for label, close in pairs]
    return {"data": {"code": "600519", "klines": rows}}


def test_eastmoney_label_is_bar_end():
    """标签 T 的 bar 覆盖 [T-period, T]，不是 [T, T+period]。"""
    bars = _parse_eastmoney_klines(
        _em_payload([("09:35", 935), ("09:40", 940)]), "600519", 5,
        datetime(2026, 10, 13, 9, 40),
    )
    assert bars[0].start_ts == datetime(2026, 10, 13, 9, 30)
    assert bars[0].end_ts == datetime(2026, 10, 13, 9, 35)


def test_as_of_adopts_the_bar_that_just_closed():
    """R1-01 核心：as_of=10:32 时，标签 10:30 的那根已收盘可知，必须被采纳。

    **时间戳会巧合重合，不能用时间戳区分，必须用 close：**
      修复前（标签当起点）：标签 10:25 的根 -> start 10:25, end 10:30（11 根）
      修复后（标签当终点）：标签 10:30 的根 -> start 10:25, end 10:30（12 根）
    修复前会白丢一根已经收盘的 bar，预热序列因此落后一根。
    """
    pairs = [
        ("09:35", 935), ("09:40", 940), ("09:45", 945), ("09:50", 950),
        ("09:55", 955), ("10:00", 1000), ("10:05", 1005), ("10:10", 1010),
        ("10:15", 1015), ("10:20", 1020), ("10:25", 1025), ("10:30", 1030),
        ("10:35", 1035),
    ]
    bars = _parse_eastmoney_klines(
        _em_payload(pairs), "600519", 5, datetime(2026, 10, 13, 10, 32)
    )

    assert len(bars) == 12, "09:35~10:30 共 12 根都应已收盘可知"
    assert bars[-1].close == 1030.0, "末根必须是标签 10:30 的那根（修复前是 1025）"
    assert bars[-1].start_ts == datetime(2026, 10, 13, 10, 25)
    assert bars[-1].end_ts == datetime(2026, 10, 13, 10, 30)


def test_last_bar_of_day_ends_at_close():
    """当日末根应为 14:55~15:00，不得越界成 15:00~15:05。"""
    bars = _parse_eastmoney_klines(
        _em_payload([("14:55", 1455), ("15:00", 1500)]), "600519", 5,
        datetime(2026, 10, 13, 15, 0),
    )
    assert bars[-1].start_ts == datetime(2026, 10, 13, 14, 55)
    assert bars[-1].end_ts == datetime(2026, 10, 13, 15, 0)


def test_forming_bar_is_still_skipped():
    """对照：as_of 早于标签时，那根确实还在 forming，必须丢弃。"""
    bars = _parse_eastmoney_klines(
        _em_payload([("09:35", 935), ("09:40", 940)]), "600519", 5,
        datetime(2026, 10, 13, 9, 39),
    )
    assert len(bars) == 1
    assert bars[-1].end_ts == datetime(2026, 10, 13, 9, 35)


# --------------------------------------------------------------------------
# R4-03：北交所此前无开关且默认放行，与两创口径不一致
# --------------------------------------------------------------------------

@pytest.mark.parametrize(
    "code,exchange,expected",
    [
        ("920627", "BJ", True),      # 新代码段 + BJ
        ("920627", "SH", True),      # 代码段可独立识别（exchange 写法异常时不漏判）
        ("430047", "SZ", True),      # 存量段
        ("830799", None, True),
        ("600519", "SH", False),
        ("300750", "SZ", False),
        ("688981", "SH", False),
    ],
)
def test_is_bse_detects_by_code_segment_or_exchange(code, exchange, expected):
    assert is_bse(code, exchange) is expected


def test_board_blocked_covers_bse_and_defaults_to_blocked():
    """修复前北交所返回 None（默认放行），两创却被拦。"""
    bse = Candidate(stock_code="920627", stock_name="北交所样本", exchange_type="BJ")
    assert bse.board_blocked(False, False) == "BOARD_BLOCKED_BSE"
    assert bse.board_blocked(False, False, allow_bse=True) is None
    # 两创/主板行为不变
    assert Candidate("300750", "宁德", "SZ").board_blocked(False, False) == "BOARD_BLOCKED_CHINEXT"
    assert Candidate("600519", "茅台", "SH").board_blocked(False, False) is None


def test_allow_bse_defaults_false_and_can_be_enabled_by_env():
    env = {"PAPER_WATCH_EXECUTION_MODE": "INTERNAL_PAPER", "PAPER_KLINE_SOURCE": "eastmoney"}
    assert PaperConfig.from_env(dict(env)).allow_bse is False
    assert PaperConfig.from_env({**env, "PAPER_ALLOW_BSE": "1"}).allow_bse is True
    assert PaperConfig(api_key="k").allow_bse is False


def _bse_quote() -> Quote:
    return Quote(
        code="920627", name="北交所样本", exchange="BJ", last=10.0, pct_chg=1.0,
        volume=1000, market_time=_T, received_at=_T, validity=QuoteValidity.VALID,
        limit_up_price=13.0, limit_up_source="test",
    )


def test_check_watch_intent_blocks_bse_buy_by_default():
    intent = Intent("920627", IntentType.BUY_LIMIT, side="buy", qty=100, limit_price=10.0)
    out = check_watch_intent(
        intent, _bse_quote(), Balance(total_assets=100_000, avail_balance=100_000),
        {}, [], PaperConfig(api_key="k"),
    )
    assert out.risk_reject_reason == "BOARD_BLOCKED_BSE"


def test_check_watch_intent_allows_bse_buy_when_enabled():
    intent = Intent("920627", IntentType.BUY_LIMIT, side="buy", qty=100, limit_price=10.0)
    out = check_watch_intent(
        intent, _bse_quote(), Balance(total_assets=100_000, avail_balance=100_000),
        {}, [], PaperConfig(api_key="k", allow_bse=True),
    )
    assert out.risk_reject_reason != "BOARD_BLOCKED_BSE"


# --------------------------------------------------------------------------
# R1-03：开盘前启动时，当日首根**完整** bar 不得被丢弃
# --------------------------------------------------------------------------

_WARMUP_BAR = WatchBar(
    code="600519", period_min=5, open=10.0, high=10.0, low=10.0, close=10.0,
    volume=1000.0, start_ts=datetime(2026, 10, 12, 14, 55),
    end_ts=datetime(2026, 10, 12, 15, 0), closed=True,
)


def _snap(code_quote: str, at: datetime, volume: float) -> Quote:
    return Quote(
        code=code_quote, name="测试", exchange="SH", last=10.0, pct_chg=0.0,
        volume=volume, market_time=at, received_at=at, validity=QuoteValidity.VALID,
        limit_up_price=11.0, limit_up_source="test",
    )


def _seeded_store() -> BarStore:
    store = BarStore(period_min=5)
    store.seed("600519", [_WARMUP_BAR], discard_first_forming=True)
    return store


def test_first_complete_bucket_is_kept_when_started_at_the_open():
    """R1-03：09:30:05 启动时，09:30~09:35 是完整首根 bar，必须入 history。

    修复前它被当成「残段」无条件丢弃，当日 history 直到 09:35 才出现，
    每天白丢一根（实测 `今日 history=['09:35']`）。
    """
    code = "600519"
    store = _seeded_store()

    store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 9, 30, 5), 100), now=datetime(2026, 10, 13, 9, 30, 5))
    assert store.history(code)[-1] is _WARMUP_BAR, "此时首桶还没结束"

    store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 9, 35, 5), 200), now=datetime(2026, 10, 13, 9, 35, 5))
    assert store.history(code)[-1].start_ts == datetime(2026, 10, 13, 9, 30), (
        "09:30 桶是完整首根，必须保留（修复前被丢弃）"
    )

    store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 9, 40, 5), 300), now=datetime(2026, 10, 13, 9, 40, 5))
    assert [b.start_ts.strftime("%H:%M") for b in store.history(code)[-2:]] == ["09:30", "09:35"]


def test_mid_bucket_start_still_discards_the_residual_bucket():
    """对照：10:32 才接入时，10:30 桶只是残段，必须继续丢弃。

    这条锁住「不得笼统取消 discard」——只保留真正完整的首桶。
    """
    code = "600519"
    store = _seeded_store()

    store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 10, 32, 0), 100), now=datetime(2026, 10, 13, 10, 32, 0))
    store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 10, 35, 5), 200), now=datetime(2026, 10, 13, 10, 35, 5))
    assert store.history(code)[-1] is _WARMUP_BAR, "残段 10:30 桶不得入 history"

    store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 10, 40, 5), 300), now=datetime(2026, 10, 13, 10, 40, 5))
    assert store.history(code)[-1].start_ts == datetime(2026, 10, 13, 10, 35), "下一桶应正常收盘"


# --------------------------------------------------------------------------
# R7-02：valid_until 的「下一根 bar」必须在午休处接续到 13:00
# --------------------------------------------------------------------------

def test_next_bar_end_resumes_after_lunch_break():
    """11:30 收盘的那根，下一根是 13:00~13:05。

    修复前一律算 end + period，得到 11:35，与 invalidation_conditions 声明的
    「下一根 bar 收盘」不符。注意 14:55 的窗口本就是 300 秒，零窗口在 15:00 末根
    （那是 R1-04 的产品决策项，不在本修复范围）。
    """
    from paper.watch.signal_adapter import next_bar_end

    assert next_bar_end(datetime(2026, 10, 13, 11, 30), 5) == datetime(2026, 10, 13, 13, 5)
    assert next_bar_end(datetime(2026, 10, 13, 11, 25), 5) == datetime(2026, 10, 13, 11, 30)
    assert next_bar_end(datetime(2026, 10, 13, 14, 55), 5) == datetime(2026, 10, 13, 15, 0)
    assert next_bar_end(datetime(2026, 10, 13, 10, 0), 5) == datetime(2026, 10, 13, 10, 5)


def test_adapter_signal_at_lunch_break_is_valid_until_1305():
    """集成：走到 11:30 那根 bar 时，信号的 valid_until 应为 13:05。"""
    from paper.watch.signal_adapter import SignalAdapter

    adapter = SignalAdapter(strategy_name="MA_CROSS", ma_short=5, ma_long=20)
    code = "600519"
    base = datetime(2026, 10, 13, 9, 40)
    bars = [
        WatchBar(
            code=code, period_min=5, open=10.0, high=10.0, low=10.0, close=10.0 + i * 0.01,
            volume=1000.0, start_ts=base + timedelta(minutes=5 * i),
            end_ts=base + timedelta(minutes=5 * (i + 1)), closed=True,
        )
        for i in range(20)
    ]
    adapter.seed(code, bars)
    # 第 21 根，结束于 11:30（前 20 根为 09:40~11:20）
    last = WatchBar(
        code=code, period_min=5, open=10.2, high=10.2, low=10.2, close=10.2,
        volume=1000.0, start_ts=datetime(2026, 10, 13, 11, 25),
        end_ts=datetime(2026, 10, 13, 11, 30), closed=True,
    )
    signal = adapter.on_closed_bar(code, last, position_qty=0)

    assert signal.valid_until.startswith("2026-10-13T13:05"), (
        f"11:30 bar 的信号应有效到下一根收盘 13:05；实际 {signal.valid_until}"
    )


# --------------------------------------------------------------------------
# R1-02：残段被丢弃后必须能被补回来（否则留下永久空洞）
# --------------------------------------------------------------------------

def test_discarded_residual_bucket_is_reported_to_caller():
    """丢弃残段是对的，但必须**报告**给调用方，否则没人能补它。"""
    code = "600519"
    store = _seeded_store()

    at = datetime(2026, 10, 13, 10, 32, 0)
    update = store.update_snapshot(code, _snap(code, at, 100), now=at)
    assert update.discarded == [], "首桶还没结束，此刻不该有丢弃"

    at2 = datetime(2026, 10, 13, 10, 35, 5)
    update2 = store.update_snapshot(code, _snap(code, at2, 200), now=at2)

    assert [bar.start_ts for bar in update2.discarded] == [datetime(2026, 10, 13, 10, 30)], (
        "10:30 桶是残段应被丢弃，且必须出现在 discarded 里供补拉"
    )
    assert store.history(code)[-1] is _WARMUP_BAR, "残段本身不入 history"


def test_insert_closed_merges_gap_without_replacing_history():
    """补洞是**合并**：不替换历史、不去重已存在的区间、保持有序。"""
    code = "600519"
    store = _seeded_store()
    before = store.history(code)

    def mk(hh, mm, close):
        return WatchBar(
            code=code, period_min=5, open=close, high=close, low=close, close=close,
            volume=100.0, start_ts=datetime(2026, 10, 13, hh, mm),
            end_ts=datetime(2026, 10, 13, hh, mm) + timedelta(minutes=5), closed=True,
        )

    added = store.insert_closed(code, [mk(9, 30, 11.0), mk(9, 35, 12.0)])
    assert [bar.start_ts.strftime("%H:%M") for bar in added] == ["09:30", "09:35"]
    hist = store.history(code)
    assert hist[0] is _WARMUP_BAR, "既有历史必须保留"
    assert [bar.start_ts.strftime("%H:%M") for bar in hist[-2:]] == ["09:30", "09:35"]

    # 重复插入应无新增，且不得产生重复区间
    assert store.insert_closed(code, [mk(9, 30, 99.0)]) == []
    starts = [bar.start_ts for bar in store.history(code)]
    assert len(starts) == len(set(starts)), "不得出现重复 start_ts"
    assert [bar.close for bar in store.history(code) if bar.start_ts.minute == 30][0] == 11.0, (
        "补洞写入的权威 bar 不得被重复插入覆盖"
    )


def test_local_residual_never_overwrites_authoritative_backfill():
    """R1-02 守卫：权威补洞已写入的区间，本地残段滚落时不得覆盖、不得重复。

    本地残段缺了开头一段，质量比权威 K 线差；让它在后面覆盖，等于把补洞白做。
    """
    code = "600519"
    store = BarStore(period_min=5)
    store.seed(code, [_WARMUP_BAR])

    auth = WatchBar(
        code=code, period_min=5, open=11.0, high=11.0, low=11.0, close=11.0,
        volume=100.0, start_ts=datetime(2026, 10, 13, 10, 30),
        end_ts=datetime(2026, 10, 13, 10, 35), closed=True,
    )
    assert store.insert_closed(code, [auth]) == [auth]

    # 本地残段：10:32 接入时建立的 forming 桶，10:35 滚落
    store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 10, 32), 100), now=datetime(2026, 10, 13, 10, 32))
    update = store.update_snapshot(code, _snap(code, datetime(2026, 10, 13, 10, 35, 5), 200), now=datetime(2026, 10, 13, 10, 35, 5))

    assert update.discarded == [], "权威已补齐的区间，其残段不能再走丢弃分支"
    assert update.new_closed == [], "权威已补齐的区间，不得再追加本地残段"
    history = store.history(code)
    starts = [bar.start_ts for bar in history]
    assert len(starts) == len(set(starts)), "不得出现重复 start_ts"
    kept = [bar for bar in history if bar.start_ts == datetime(2026, 10, 13, 10, 30)]
    assert len(kept) == 1 and kept[0].close == 11.0, "必须保留权威 bar，而非本地残段"


# --------------------------------------------------------------------------
# R1-05：妙想分钟 K 线的时间标签口径未取证 -> 来源选择处 fail-closed
# --------------------------------------------------------------------------

def test_mx_query_kline_source_refuses_unverified_label_semantics(monkeypatch):
    """R1-05：标签口径没取证时必须**拒绝运行**，而不是猜一个。

    东财已确证「标签 = bar 结束时刻」，妙想侧无证据且解析器仍按「起始时刻」
    解释——两种口径相差整整一个周期，默默启用会得到系统性平移 5 分钟的序列。
    """
    from paper.config import PaperConfig
    from paper.watch import kline_client
    from paper.watch.kline_client import KlineClient

    monkeypatch.delenv(kline_client.MX_LABEL_CONFIRMED_ENV, raising=False)
    assert kline_client.mx_kline_label_confirmed() is False

    config = PaperConfig.from_env({"PAPER_KLINE_SOURCE": "mx_query", "MX_APIKEY": "k"})

    class _HttpMustNotBeUsed:
        def post(self, *args, **kwargs):
            raise AssertionError("未确认标签口径前不得发起妙想 K 线请求")

    client = KlineClient(_HttpMustNotBeUsed(), config)
    bars, result = client.fetch_history("600519", "贵州茅台", 5, 30)

    assert bars is None
    assert result.ok is False
    assert result.code == "MX_KLINE_LABEL_SEMANTICS_UNVERIFIED"
    assert kline_client.MX_LABEL_CONFIRMED_ENV in result.message, "报错必须给出放行办法"


def test_mx_kline_opt_in_flag_is_explicit(monkeypatch):
    """放行必须是显式的：只有精确的 '1' 才算确认。"""
    from paper.watch.kline_client import MX_LABEL_CONFIRMED_ENV, mx_kline_label_confirmed

    for value in ("0", "", "true", "yes"):
        monkeypatch.setenv(MX_LABEL_CONFIRMED_ENV, value)
        assert mx_kline_label_confirmed() is False, f"{value!r} 不应视为已确认"
    monkeypatch.setenv(MX_LABEL_CONFIRMED_ENV, "1")
    assert mx_kline_label_confirmed() is True
