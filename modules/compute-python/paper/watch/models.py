"""watch 领域对象。"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime


@dataclass
class WatchBar:
    code: str
    period_min: int
    open: float
    high: float
    low: float
    close: float
    volume: float
    start_ts: datetime
    end_ts: datetime
    closed: bool = False
    source: str = "snapshot"

    def to_dict(self) -> dict:
        data = asdict(self)
        data["start_ts"] = self.start_ts.isoformat()
        data["end_ts"] = self.end_ts.isoformat()
        return data


@dataclass
class WatchSignal:
    code: str
    action: str
    strategy: str
    bar_ts: str
    reason_codes: list[str] = field(default_factory=list)
    # 信号过程值（如 MA5/MA20 末两值），供监控面板展示计算依据
    detail: dict = field(default_factory=dict)
    strategy_version: str = ""
    bar_period_min: int = 0
    holding_horizon: str = "UNVALIDATED"
    valid_until: str = ""
    invalidation_conditions: list[str] = field(default_factory=list)
    signal_status: str = "OBSERVE"
    quality_status: str = "UNKNOWN"
    quality_detail: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class BarUpdate:
    code: str
    forming: WatchBar | None
    new_closed: list[WatchBar] = field(default_factory=list)
    # R1-02：因「残段」或「数据源切换」被丢弃的 bar。丢弃本身是对的（该桶确实缺了
    # 开头一段），但调用方必须知道丢了哪一根，才能在权威数据可得后补回来，
    # 否则序列里会留下一个**永久空洞**，均线窗口悄悄跨过一段缺失时间。
    discarded: list[WatchBar] = field(default_factory=list)


@dataclass
class WatchTick:
    run_id: str
    trade_date: str
    started_at: str
    finished_at: str
    live: bool
    universe: list[dict] = field(default_factory=list)
    new_closed_bars: list[dict] = field(default_factory=list)
    signals: list[dict] = field(default_factory=list)
    intents: list[dict] = field(default_factory=list)
    submits: list[dict] = field(default_factory=list)
    account: dict = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)
    metrics: dict = field(default_factory=dict)
    status: str = "OK"

    def to_dict(self) -> dict:
        return asdict(self)
