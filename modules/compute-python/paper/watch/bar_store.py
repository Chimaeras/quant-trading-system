"""盘中快照聚合为已收盘的固定周期 OHLCV bar。"""
from __future__ import annotations

from dataclasses import replace
from datetime import datetime, timedelta

from ..models import Quote
from .models import BarUpdate, WatchBar


def floor_bucket(ts: datetime, period_min: int) -> datetime:
    if period_min <= 0 or 60 % period_min != 0:
        raise ValueError("period_min 必须是正数且能整除 60")
    return ts.replace(
        minute=(ts.minute // period_min) * period_min,
        second=0,
        microsecond=0,
    )


class BarStore:
    """每个代码维护一根 forming bar 和有限长度的 closed bar 历史。"""

    def __init__(self, period_min: int = 5, max_bars: int = 300):
        if period_min <= 0 or 60 % period_min != 0:
            raise ValueError("period_min 必须是正数且能整除 60")
        if max_bars < 2:
            raise ValueError("max_bars 必须至少为 2")
        self.period_min = period_min
        self.max_bars = max_bars
        self._history: dict[str, list[WatchBar]] = {}
        self._forming: dict[str, WatchBar] = {}
        # Quote.volume 是日内累计成交量时，forming bar 需要使用相邻快照
        # 的增量，而不是把累计值直接写入每一根分钟 bar。
        self._last_volume: dict[str, float] = {}
        # 真实 K 线预热结束于上一根已收盘 bar；首个实时快照所在桶通常只剩
        # 半根，跨桶时必须丢弃这根不完整 bar，避免拿它触发信号。
        self._discard_next_closed: set[str] = set()

    def reset(self) -> None:
        """清空跨交易日不可复用的 forming/history 状态。"""
        self._history.clear()
        self._forming.clear()
        self._last_volume.clear()
        self._discard_next_closed.clear()

    def seed(self, code: str, bars: list[WatchBar], *, discard_first_forming: bool = False) -> None:
        """注入启动预热数据；只接受已收盘、同周期、有效 close 的 bar。"""
        valid: list[WatchBar] = []
        seen: set[datetime] = set()
        for bar in sorted(bars, key=lambda item: item.start_ts):
            if bar.code != code or not bar.closed or bar.period_min != self.period_min:
                raise ValueError(f"{code} 预热 bar 周期/状态/代码不合法")
            if bar.close <= 0 or bar.high < bar.low or bar.open <= 0:
                raise ValueError(f"{code} 预热 bar 价格不合法")
            if bar.start_ts in seen:
                continue
            seen.add(bar.start_ts)
            valid.append(bar)
        self._history[code] = valid[-self.max_bars:]
        # 重新预热同一代码时，旧 forming 绝不能跨过新一轮历史数据继续聚合。
        self._forming.pop(code, None)
        self._last_volume.pop(code, None)
        if discard_first_forming:
            self._discard_next_closed.add(code)
        else:
            self._discard_next_closed.discard(code)

    def history(self, code: str) -> list[WatchBar]:
        return list(self._history.get(code, []))

    def forming(self, code: str) -> WatchBar | None:
        return self._forming.get(code)

    def update_snapshot(self, code: str, quote: Quote, now: datetime | None = None) -> BarUpdate:
        """写入快照；桶切换时恰好提交上一根 closed bar。"""
        if quote.last <= 0:
            raise ValueError(f"{code} quote.last 必须大于 0")
        observed_at = now or quote.market_time or quote.received_at or datetime.now()
        start = floor_bucket(observed_at, self.period_min)
        end = start + timedelta(minutes=self.period_min)
        current = self._forming.get(code)
        new_closed: list[WatchBar] = []

        if current is None:
            try:
                cumulative_volume = max(float(quote.volume or 0), 0.0)
            except (TypeError, ValueError):
                cumulative_volume = 0.0
            # 首个快照前的成交量来自启动前，无法安全归属到当前残段。
            self._last_volume[code] = cumulative_volume
            current = WatchBar(
                code=code,
                period_min=self.period_min,
                open=quote.last,
                high=quote.last,
                low=quote.last,
                close=quote.last,
                volume=0.0,
                start_ts=start,
                end_ts=end,
                closed=False,
                source="snapshot",
            )
            self._forming[code] = current
            return BarUpdate(code, current, new_closed)

        if start < current.start_ts:
            # 迟到快照不能污染已经建立的时间序列。
            return BarUpdate(code, current, new_closed)

        try:
            cumulative_volume = max(float(quote.volume or 0), 0.0)
        except (TypeError, ValueError):
            cumulative_volume = self._last_volume.get(code, 0.0)
        previous_volume = self._last_volume.get(code, cumulative_volume)
        # 成交量回退通常是源端修正；不把回退值当作新的一段成交量，
        # 等累计值恢复后再继续计算增量。
        volume_delta = max(cumulative_volume - previous_volume, 0.0)
        self._last_volume[code] = max(previous_volume, cumulative_volume)

        if start == current.start_ts:
            current.high = max(current.high, quote.last)
            current.low = min(current.low, quote.last)
            current.close = quote.last
            current.volume += volume_delta
            return BarUpdate(code, current, new_closed)

        # 只有收到下一个桶的快照，上一桶才被确认结束。
        if code in self._discard_next_closed:
            # 启动时的首个桶只代表从启动时刻到桶尾的残段，不足以作为完整
            # 策略 bar。丢弃它，但保留本次快照作为下一桶的 forming 起点。
            self._discard_next_closed.remove(code)
        else:
            closed = replace(current, closed=True)
            self._history.setdefault(code, []).append(closed)
            self._history[code] = self._history[code][-self.max_bars:]
            new_closed.append(closed)

        current = WatchBar(
            code=code,
            period_min=self.period_min,
            open=quote.last,
            high=quote.last,
            low=quote.last,
            close=quote.last,
            volume=volume_delta,
            start_ts=start,
            end_ts=end,
            closed=False,
            source="snapshot",
        )
        self._forming[code] = current
        return BarUpdate(code, current, new_closed)

    def required_bars(self, strategy: str, short: int = 5, long: int = 20,
                      boll_period: int = 20, macd_signal: int = 9) -> int:
        strategy = strategy.upper()
        if strategy == "MA_CROSS":
            return max(long + 1, short + 2)
        if strategy == "MACD":
            return 26 + 2 * macd_signal
        if strategy == "BOLL":
            return boll_period
        raise ValueError(f"未知 watch 策略: {strategy}")
