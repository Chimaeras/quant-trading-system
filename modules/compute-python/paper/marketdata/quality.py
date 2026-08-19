"""批次级及逐证券行情质量状态机。"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from ..models import Quote, QuoteValidity
from .models import BatchQuoteResult, QuoteQuality


@dataclass
class _CodeState:
    last_market_time: datetime | None = None
    last_received_at: datetime | None = None
    last_price: float | None = None
    last_volume: float | None = None
    consecutive_missing: int = 0
    quality: QuoteQuality = QuoteQuality.MISSING
    reason: str = "NOT_SEEN"


class MarketDataQualityEngine:
    def __init__(self, *, missing_disable_cycles: int = 2, price_jump_pct: float = 35.0,
                 clock_skew_sec: int = 30):
        self.missing_disable_cycles = max(int(missing_disable_cycles), 1)
        self.price_jump_pct = max(float(price_jump_pct), 0.0)
        self.clock_skew_sec = max(int(clock_skew_sec), 0)
        self._states: dict[str, _CodeState] = {}

    def reset(self) -> None:
        self._states.clear()

    def restore(self, rows: list[dict]) -> None:
        """恢复连续缺失和时间游标，重启后不能把异常计数清零。"""
        for row in rows:
            code = str(row.get("code", ""))
            if not code:
                continue
            try:
                quality = QuoteQuality(str(row.get("quality", "MISSING")))
            except ValueError:
                quality = QuoteQuality.MISSING
            self._states[code] = _CodeState(
                last_market_time=self._parse_time(row.get("last_market_time")),
                last_received_at=self._parse_time(row.get("last_received_at")),
                last_price=self._float_or_none(row.get("last_price")),
                last_volume=self._float_or_none(row.get("last_volume")),
                consecutive_missing=max(int(row.get("consecutive_missing", 0) or 0), 0),
                quality=quality,
                reason=str(row.get("reason", "")),
            )

    @staticmethod
    def _parse_time(value) -> datetime | None:
        try:
            return datetime.fromisoformat(str(value)) if value else None
        except ValueError:
            return None

    @staticmethod
    def _float_or_none(value) -> float | None:
        try:
            return float(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    def assess_batch(self, batch: BatchQuoteResult, *, now: datetime) -> dict[str, dict]:
        requested = set(batch.quotes) | set(batch.missing_codes) | set(batch.errors)
        outcomes: dict[str, dict] = {}
        for code in requested:
            quote = batch.quotes.get(code)
            outcomes[code] = self.assess_quote(code, quote, now=now)
        if batch.duplicate_codes:
            batch.status = "DEGRADED"
            batch.quality_reasons.append("DUPLICATE_CODES")
        if batch.missing_codes:
            batch.status = "DEGRADED"
            batch.quality_reasons.append("MISSING_CODES")
        return outcomes

    def assess_quote(self, code: str, quote: Quote | None, *, now: datetime) -> dict:
        state = self._states.setdefault(code, _CodeState())
        reasons: list[str] = []
        if quote is None:
            state.consecutive_missing += 1
            state.quality = QuoteQuality.MISSING
            state.reason = "QUOTE_MISSING"
            return self._snapshot(code, state, reasons=[state.reason])

        state.consecutive_missing = 0
        if quote.market_time and state.last_market_time and quote.market_time < state.last_market_time:
            reasons.append("MARKET_TIME_REVERSED")
        if quote.market_time and quote.market_time > now + timedelta(seconds=self.clock_skew_sec):
            reasons.append("FUTURE_MARKET_TIME")
        if quote.volume is not None and state.last_volume is not None and quote.volume < state.last_volume:
            reasons.append("CUMULATIVE_VOLUME_ROLLBACK")
        if state.last_price and quote.last > 0:
            jump = abs(quote.last / state.last_price - 1.0) * 100
            if jump > self.price_jump_pct:
                reasons.append("PRICE_JUMP")

        if quote.validity == QuoteValidity.STALE:
            quality = QuoteQuality.STALE
        elif quote.validity == QuoteValidity.INVALID or any(
            reason in {"MARKET_TIME_REVERSED", "FUTURE_MARKET_TIME"}
            for reason in reasons
        ):
            quality = QuoteQuality.INVALID
        elif quote.validity == QuoteValidity.NOT_TRADABLE:
            quality = QuoteQuality.NOT_TRADABLE
        elif reasons:
            quality = QuoteQuality.WARN
        else:
            quality = QuoteQuality.VALID

        state.last_market_time = max(
            (item for item in (state.last_market_time, quote.market_time) if item is not None),
            default=None,
        )
        state.last_received_at = quote.received_at or now
        if quote.last > 0:
            state.last_price = quote.last
        if quote.volume is not None:
            state.last_volume = max(state.last_volume or 0.0, float(quote.volume))
        state.quality = quality
        state.reason = ",".join(reasons) or quality.value
        return self._snapshot(code, state, reasons=reasons)

    def is_disabled(self, code: str) -> bool:
        state = self._states.get(code)
        return bool(state and state.consecutive_missing >= self.missing_disable_cycles)

    @staticmethod
    def _snapshot(code: str, state: _CodeState, *, reasons: list[str]) -> dict:
        return {
            "code": code,
            "quality": state.quality.value,
            "reason": state.reason,
            "reasons": list(reasons),
            "consecutive_missing": state.consecutive_missing,
            "last_market_time": state.last_market_time.isoformat() if state.last_market_time else "",
            "last_received_at": state.last_received_at.isoformat() if state.last_received_at else "",
            "last_price": state.last_price,
            "last_volume": state.last_volume,
        }
