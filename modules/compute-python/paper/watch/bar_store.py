"""盘中快照聚合为已收盘的固定周期 OHLCV bar。"""
from __future__ import annotations

from dataclasses import replace
from datetime import datetime, timedelta

from .. import clock
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


# R1-03：首个实时快照距「桶起点」的容差（秒）。
# 超出容差说明是桶中途才接入，该桶确实是被截断的**残段**，应丢弃；
# 落在容差内说明基本从桶起点就在观察（典型的开盘前/刚开盘启动），
# 该桶是**完整的当日首根 bar**，必须保留。
_FIRST_BUCKET_TOLERANCE_SEC = 60.0


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
        # R1-03：只由 seed(discard_first_forming=True) 设置的标记，用于在首个实时
        # 快照到来时判断「这一桶是不是残段」。与 _discard_next_closed 分开，是因为
        # 后者还承载 restore_forming(discard_on_close=True) 与行情源切换两种语义，
        # 那两种必须无条件丢弃，不能被偏移判定撤掉。
        self._startup_residual_pending: set[str] = set()
        # R1-02：由 insert_closed 补入的**权威**区间。本地残段滚落时不得覆盖它们
        # （本地残段缺了开头一段，质量更差），也不得追加成重复 start_ts。
        # 只跟踪补洞写入的区间，是因为 seed 预热与实时桶重叠是合法且常见的。
        self._authoritative_starts: dict[str, set[datetime]] = {}

    def reset(self) -> None:
        """清空跨交易日不可复用的 forming/history 状态。"""
        self._history.clear()
        self._forming.clear()
        self._last_volume.clear()
        self._discard_next_closed.clear()
        self._startup_residual_pending.clear()
        self._authoritative_starts.clear()

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
        # 预热会替换整段历史，之前的补洞标记随之失效。
        self._authoritative_starts.pop(code, None)
        if discard_first_forming:
            self._discard_next_closed.add(code)
            self._startup_residual_pending.add(code)
        else:
            self._discard_next_closed.discard(code)
            self._startup_residual_pending.discard(code)

    def history(self, code: str) -> list[WatchBar]:
        return list(self._history.get(code, []))

    def forming(self, code: str) -> WatchBar | None:
        return self._forming.get(code)

    def restore_forming(
        self,
        code: str,
        bar: WatchBar,
        *,
        last_cumulative_volume: float,
        discard_on_close: bool = False,
    ) -> None:
        """恢复同一交易日的 forming bar 与累计成交量游标。"""
        if bar.code != code or bar.closed or bar.period_min != self.period_min:
            raise ValueError(f"{code} forming bar 周期/状态/代码不合法")
        if bar.close <= 0 or bar.open <= 0 or bar.high < bar.low:
            raise ValueError(f"{code} forming bar 价格不合法")
        self._forming[code] = replace(bar, closed=False)
        self._last_volume[code] = max(float(last_cumulative_volume or 0.0), 0.0)
        if discard_on_close:
            self._discard_next_closed.add(code)
        else:
            self._discard_next_closed.discard(code)

    def checkpoint(self, code: str) -> dict | None:
        """导出可原样恢复的 forming bar 断点。"""
        bar = self._forming.get(code)
        if bar is None:
            return None
        return {
            "bar": replace(bar),
            "last_cumulative_volume": self._last_volume.get(code, 0.0),
            "discard_on_close": code in self._discard_next_closed,
        }

    def update_snapshot(self, code: str, quote: Quote, now: datetime | None = None) -> BarUpdate:
        """写入快照；桶切换时恰好提交上一根 closed bar。"""
        if quote.last <= 0:
            raise ValueError(f"{code} quote.last 必须大于 0")
        observed_at = now or quote.market_time or quote.received_at or clock.now()
        start = floor_bucket(observed_at, self.period_min)
        end = start + timedelta(minutes=self.period_min)
        current = self._forming.get(code)
        new_closed: list[WatchBar] = []
        discarded: list[WatchBar] = []

        if current is None:
            try:
                cumulative_volume = max(float(quote.volume or 0), 0.0)
            except (TypeError, ValueError):
                cumulative_volume = 0.0
            # 首个快照前的成交量来自启动前，无法安全归属到当前残段。
            self._last_volume[code] = cumulative_volume
            # R1-03：判定这一桶到底是不是「残段」。
            # 旧实现无条件丢弃，于是开盘前/刚开盘启动时连**完整的当日首根 bar**
            # 也一并丢掉（实测：09:30:05 启动时首根完整桶 09:30~09:35 未保留，
            # 当日 history 直到 09:35 才出现）。只在真正中途接入时才丢。
            if code in self._startup_residual_pending:
                self._startup_residual_pending.discard(code)
                if (observed_at - start).total_seconds() <= _FIRST_BUCKET_TOLERANCE_SEC:
                    self._discard_next_closed.discard(code)
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
                source=quote.source or "snapshot",
            )
            self._forming[code] = current
            return BarUpdate(code, current, new_closed)

        if start < current.start_ts:
            # 迟到快照不能污染已经建立的时间序列。
            return BarUpdate(code, current, new_closed)

        if current.source != (quote.source or "snapshot"):
            # 不同供应商的复权、成交量和时间口径可能不同；源切换时当前
            # forming bar 作废并从新源重新起桶，绝不拼接成一根可交易 bar。
            self._discard_next_closed.add(code)
            try:
                cumulative_volume = max(float(quote.volume or 0), 0.0)
            except (TypeError, ValueError):
                cumulative_volume = 0.0
            self._last_volume[code] = cumulative_volume
            current = WatchBar(
                code=code, period_min=self.period_min,
                open=quote.last, high=quote.last, low=quote.last, close=quote.last,
                volume=0.0, start_ts=start, end_ts=end, closed=False,
                source=quote.source or "snapshot",
            )
            self._forming[code] = current
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
            # R1-02：同时写进 discarded，交给调用方在权威 K 线可得后补回来，
            # 否则这个空洞会永久留在序列里（实测 10:32 重启后 10:25 直接跳到 10:35）。
            self._discard_next_closed.remove(code)
            self._startup_residual_pending.discard(code)
            discarded.append(replace(current, closed=True))
        else:
            closed = replace(current, closed=True)
            history = self._history.setdefault(code, [])
            # R1-02：若这一区间已由权威来源补洞写入（insert_closed），本地残段
            # 不得覆盖它——本地这根缺了开头一段，质量更差——也不得追加成重复项。
            if closed.start_ts in self._authoritative_starts.get(code, ()):
                pass
            else:
                history.append(closed)
                self._history[code] = history[-self.max_bars:]
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
            source=quote.source or "snapshot",
        )
        self._forming[code] = current
        return BarUpdate(code, current, new_closed, discarded)

    def insert_closed(self, code: str, bars: list[WatchBar]) -> list[WatchBar]:
        """把权威来源的已收盘 bar **合并**进历史，返回实际新增的 bar（R1-02）。

        与 :meth:`seed` 的区别：这里不替换历史、不动 forming、不改丢弃标记——
        只把历史中缺失的区间补上（按 ``start_ts`` 去重），并保持有序与长度上限。
        用于修补「重启残段被丢弃」留下的空洞。
        """
        valid = [
            bar for bar in bars
            if bar.code == code
            and bar.closed
            and bar.period_min == self.period_min
            and bar.open > 0
            and bar.close > 0
            and bar.high >= bar.low
        ]
        if not valid:
            return []
        merged = {bar.start_ts: bar for bar in self._history.get(code, [])}
        added: list[WatchBar] = []
        for bar in valid:
            if bar.start_ts in merged:
                continue
            merged[bar.start_ts] = bar
            added.append(bar)
        if not added:
            return []
        self._history[code] = [merged[key] for key in sorted(merged)][-self.max_bars:]
        self._authoritative_starts.setdefault(code, set()).update(
            bar.start_ts for bar in added
        )
        return added

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
