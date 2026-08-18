"""交易日历服务（P1-03）：可注入，节假日/调休不可用时 confirm 阻断。

禁止只用周一至周五判断交易日：法定节假日、调休工作日、临时休市
必须由日历数据源提供；日历不可用时 confirm 必须 BLOCKED。
"""
from __future__ import annotations

from datetime import date, datetime


class TradingCalendarService:
    """交易日历协议：子类提供节假日集合与可用性。"""

    def __init__(self, *, holidays: set[str] | None = None, extra_workdays: set[str] | None = None,
                 available: bool = True, source: str = "static"):
        self._holidays = holidays or set()
        self._extra_workdays = extra_workdays or set()
        self.available = available
        self.source = source

    def is_trading_day(self, day: date | datetime) -> bool:
        d = day.date() if isinstance(day, datetime) else day
        key = d.isoformat()
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
    """从 JSON 文件加载日历：{"holidays": ["2026-10-01", ...], "extra_workdays": [...]}。"""
    import json
    from pathlib import Path

    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return TradingCalendarService(
        holidays={str(d) for d in data.get("holidays", [])},
        extra_workdays={str(d) for d in data.get("extra_workdays", [])},
        source=f"file:{Path(path).name}",
    )
