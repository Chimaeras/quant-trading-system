"""分钟线本地沉淀客户端（D-STALE-BAR-01 修复 4）。

盯盘产生的 5 分钟 bar 此前只落在 Python 侧的 sqlite `closed_bar` 缓存里，而那张表
的职责是「同一交易日重启恢复」——它既不清理也无日期边界，最终跨月陈旧 bar 混入
策略预热序列，在均线断层处伪造出金叉买入。

本模块把同一批 bar 沉淀到主库 `market_data_minute`，并支持按代码批量取回**连续**
序列，使预热可以优先使用本地分钟线、外部行情退化为兜底。

设计约束：全部方法 **best-effort**，绝不向上抛异常。盯盘的行情判定不允许因为
本地沉淀服务不可用而中断——Java 停摆时只应退化为「不沉淀 + 走外部行情」。
"""
from __future__ import annotations

from datetime import datetime

import requests

from .models import WatchBar

_BULK_PATH = "/api/data/minute-bars/bulk"
_RECENT_PATH = "/api/data/minute-bars/recent"
# 单次提交上限，与 Java 侧 MAX_BULK_IMPORT_SIZE(50000) 留足余量：
# 预热序列一次性回填时可达 代码数 × 80 根，分片避免单个超大请求。
_PUSH_CHUNK = 2000


class MinuteBarClient:
    """`market_data_minute` 的读写客户端（经 Java `/api/data/minute-bars`）。"""

    def __init__(
        self,
        base_url: str,
        token: str = "",
        *,
        period_min: int = 5,
        timeout: float = 8.0,
    ):
        self._base = str(base_url or "").strip().rstrip("/")
        self._token = token or ""
        self._period_min = int(period_min)
        self._timeout = float(timeout)
        self.last_error = ""

    @property
    def enabled(self) -> bool:
        return bool(self._base)

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self._token}"} if self._token else {}

    def push(self, bars) -> int:
        """提交已收盘 bar，返回成功提交根数；失败返回 0 并记录 `last_error`。"""
        if not self.enabled:
            return 0
        payload = []
        for bar in bars:
            item = _bar_payload(bar)
            if item is not None:
                payload.append(item)
        if not payload:
            return 0
        saved = 0
        for start in range(0, len(payload), _PUSH_CHUNK):
            chunk = payload[start:start + _PUSH_CHUNK]
            try:
                response = requests.post(
                    f"{self._base}{_BULK_PATH}",
                    json=chunk,
                    headers=self._headers(),
                    timeout=self._timeout,
                )
                response.raise_for_status()
                body = response.json()
            except (requests.RequestException, ValueError) as exc:
                self.last_error = f"{type(exc).__name__}:{exc}"
                return saved
            data = body.get("data") if isinstance(body, dict) else None
            if isinstance(data, dict):
                try:
                    saved += int(data.get("saved", 0) or 0)
                except (TypeError, ValueError):
                    pass
        self.last_error = ""
        return saved

    def recent(
        self,
        codes,
        *,
        limit_per_code: int = 80,
        lookback_days: int = 5,
    ) -> dict[str, list[WatchBar]]:
        """按代码取最近 N 根 bar；失败返回空字典（调用方应回退到外部行情）。"""
        if not self.enabled:
            return {}
        wanted = [str(code).strip() for code in codes if str(code).strip()]
        if not wanted:
            return {}
        try:
            response = requests.post(
                f"{self._base}{_RECENT_PATH}",
                json={
                    "codes": wanted,
                    "period_min": self._period_min,
                    "limit_per_code": int(limit_per_code),
                    "lookback_days": int(lookback_days),
                },
                headers=self._headers(),
                timeout=self._timeout,
            )
            response.raise_for_status()
            body = response.json()
        except (requests.RequestException, ValueError) as exc:
            self.last_error = f"{type(exc).__name__}:{exc}"
            return {}
        data = body.get("data") if isinstance(body, dict) else None
        grouped = data.get("bars_by_code") if isinstance(data, dict) else None
        if not isinstance(grouped, dict):
            self.last_error = "响应缺少 data.bars_by_code"
            return {}
        out: dict[str, list[WatchBar]] = {}
        for code, rows in grouped.items():
            if not isinstance(rows, list):
                continue
            bars = [parsed for parsed in (_parse_bar(code, row) for row in rows) if parsed is not None]
            if bars:
                out[str(code)] = bars
        self.last_error = ""
        return out


def _bar_payload(bar: WatchBar) -> dict | None:
    """把 WatchBar 转成 Java 侧 `MinuteBarItem` 的 snake_case 线上格式。"""
    try:
        start = bar.start_ts
        end = bar.end_ts
        if not isinstance(start, datetime) or not isinstance(end, datetime):
            return None
        close = float(bar.close)
        if close <= 0:
            return None
        return {
            "stock_code": str(bar.code),
            "period_min": int(bar.period_min),
            # 交易日由 bar 起始时刻推导，避免调用方传入与时间戳不一致的日期
            "trade_date": start.date().isoformat(),
            "start_ts": start.isoformat(),
            "end_ts": end.isoformat(),
            "open": float(bar.open),
            "high": float(bar.high),
            "low": float(bar.low),
            "close": close,
            "volume": int(bar.volume or 0),
            "source": str(bar.source or "unknown"),
        }
    except (AttributeError, TypeError, ValueError):
        return None


def _parse_bar(code: str, row) -> WatchBar | None:
    if not isinstance(row, dict):
        return None
    try:
        return WatchBar(
            code=str(row.get("code") or code),
            period_min=int(row["period_min"]),
            open=float(row["open"]),
            high=float(row["high"]),
            low=float(row["low"]),
            close=float(row["close"]),
            volume=float(row.get("volume") or 0.0),
            start_ts=datetime.fromisoformat(str(row["start_ts"])),
            end_ts=datetime.fromisoformat(str(row["end_ts"])),
            closed=True,
            source=str(row.get("source") or "local_minute"),
        )
    except (KeyError, TypeError, ValueError):
        return None
