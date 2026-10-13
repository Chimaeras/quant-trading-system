"""交易日历服务（P1-03）：可注入，节假日/调休不可用时 confirm 阻断。

禁止只用周一至周五判断交易日：法定节假日、调休工作日、临时休市
必须由日历数据源提供；日历不可用时 confirm 必须 BLOCKED。
"""
from __future__ import annotations

from datetime import date, datetime


def _parse_day(value) -> date | None:
    """把 ``date`` / ``datetime`` / ISO 字符串统一成 ``date``；缺失或非法返回 ``None``。"""
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None


class TradingCalendarService:
    """交易日历协议：子类提供节假日集合与可用性。

    R6-02（2026-10-13）：新增**覆盖区间**概念。此前加载后无条件 ``available=True``，
    且对未命中的日期回退 ``weekday() < 5``，于是超出数据权威范围的日期（例如
    ``trading-calendar.2026.json`` 只到 2026-10-07，之后的 2027 年）会被**当作交易日**
    放行，法定节假日全部失效。现在：声明了覆盖区间后，区间外日期一律**按非交易日
    处理**（fail-closed），并可通过 :meth:`covers` / :meth:`available_for` 区分
    「区间内确实休市」与「日历不覆盖该日」。

    未声明覆盖区间时行为不变（仍走周一至周五回退），以免影响既有调用方与测试。
    """

    def __init__(self, *, holidays: set[str] | None = None, extra_workdays: set[str] | None = None,
                 available: bool = True, source: str = "static",
                 coverage_from: str | date | None = None,
                 coverage_to: str | date | None = None):
        self._holidays = holidays or set()
        self._extra_workdays = extra_workdays or set()
        self.available = available
        self.source = source
        self._coverage_from = _parse_day(coverage_from)
        self._coverage_to = _parse_day(coverage_to)

    def coverage_window(self) -> tuple[date, date] | None:
        """日历的权威覆盖区间 ``[from, to]``；未声明时返回 ``None``。"""
        if self._coverage_from is None or self._coverage_to is None:
            return None
        return (self._coverage_from, self._coverage_to)

    def covers(self, day: date | datetime) -> bool:
        """该日是否落在权威覆盖区间内。

        未声明覆盖区间时返回 ``True``（沿用旧行为，由调用方自行承担风险）。
        """
        window = self.coverage_window()
        if window is None:
            return True
        d = _parse_day(day)
        if d is None:
            return False
        return window[0] <= d <= window[1]

    def available_for(self, day: date | datetime) -> bool:
        """该日的日历是否**可用**：来源可用 **且** 该日在覆盖区间内。

        与 ``self.available`` 的区别：``available`` 只表示"配了数据源"，
        不表示"这个日期在数据源的权威范围内"。
        """
        return bool(self.available) and self.covers(day)

    def is_trading_day(self, day: date | datetime) -> bool:
        d = _parse_day(day)
        if d is None:
            return False
        key = d.isoformat()
        # R6-02：覆盖区间外不得当作交易日。旧实现无条件回退 weekday()<5，
        # 于是超出假日表范围的年份（如 2027）全部工作日都被放行。
        if not self.covers(d):
            return False
        if key in self._extra_workdays:
            return True  # 调休工作日（周末补班）
        if key in self._holidays:
            return False  # 法定节假日/临时休市
        return d.weekday() < 5

    def unavailable_reason(self) -> str:
        return "交易日历不可用（未注入节假日数据源）"


class UnavailableCalendar(TradingCalendarService):
    """未配置日历时的占位：confirm 阶段必须阻断（P1-03 fail-closed）。"""

    def __init__(self):
        super().__init__(available=False, source="unavailable")

    def is_trading_day(self, day) -> bool:
        return False


def load_calendar_from_file(path) -> TradingCalendarService:
    """从 JSON 文件加载日历。

    格式::

        {
          "coverage": {"from": "2026-01-01", "to": "2026-12-31"},
          "holidays": ["2026-10-01", ...],
          "extra_workdays": ["2026-10-10", ...]
        }

    ``coverage`` 声明该文件**权威覆盖**的日期区间（R6-02）。缺失时不再回退"整年有效"
    ——那正是旧实现把超范围日期当成交易日的根因；此处保持 ``None`` 交由
    :meth:`TradingCalendarService.covers` 处理（未声明 = 不启用覆盖校验）。
    """
    import json
    from pathlib import Path

    data = json.loads(Path(path).read_text(encoding="utf-8"))
    coverage = data.get("coverage") if isinstance(data.get("coverage"), dict) else {}
    return TradingCalendarService(
        holidays={str(d) for d in data.get("holidays", [])},
        extra_workdays={str(d) for d in data.get("extra_workdays", [])},
        source=f"file:{Path(path).name}",
        coverage_from=coverage.get("from"),
        coverage_to=coverage.get("to"),
    )
