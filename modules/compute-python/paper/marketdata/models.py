"""行情数据平面的稳定领域对象。"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum

from ..models import Quote


class MarketDataCapability(str, Enum):
    FULL_SNAPSHOT = "FULL_SNAPSHOT"
    EXECUTION_QUOTE = "EXECUTION_QUOTE"
    HISTORY_BAR = "HISTORY_BAR"
    PUSH_TICK = "PUSH_TICK"


class QuoteQuality(str, Enum):
    VALID = "VALID"
    WARN = "WARN"
    STALE = "STALE"
    INVALID = "INVALID"
    MISSING = "MISSING"
    NOT_TRADABLE = "NOT_TRADABLE"


@dataclass(frozen=True)
class ProviderHealth:
    provider: str
    ok: bool
    capabilities: tuple[MarketDataCapability, ...]
    checked_at: datetime
    message: str = ""
    max_symbols_per_request: int | None = None
    recommended_interval_sec: int | None = None

    def to_dict(self) -> dict:
        return {
            "provider": self.provider,
            "ok": self.ok,
            "capabilities": [item.value for item in self.capabilities],
            "checked_at": self.checked_at.isoformat(),
            "message": self.message,
            "max_symbols_per_request": self.max_symbols_per_request,
            "recommended_interval_sec": self.recommended_interval_sec,
        }


@dataclass
class BatchQuoteResult:
    batch_id: str
    provider: str
    requested_count: int
    started_at: datetime
    received_at: datetime
    finished_at: datetime
    quotes: dict[str, Quote] = field(default_factory=dict)
    errors: dict[str, str] = field(default_factory=dict)
    missing_codes: list[str] = field(default_factory=list)
    duplicate_codes: list[str] = field(default_factory=list)
    page_count: int = 0
    status: str = "OK"
    quality_reasons: list[str] = field(default_factory=list)

    @property
    def returned_count(self) -> int:
        return len(self.quotes)

    @property
    def covered_count(self) -> int:
        return sum(
            1 for quote in self.quotes.values()
            if quote.validity.value in {"VALID", "NOT_TRADABLE"}
        )

    @property
    def coverage(self) -> float:
        return self.covered_count / self.requested_count if self.requested_count else 0.0

    @property
    def elapsed_ms(self) -> int:
        return max(int((self.finished_at - self.started_at).total_seconds() * 1000), 0)

    def to_summary(self) -> dict:
        return {
            "batch_id": self.batch_id,
            "provider": self.provider,
            "requested_count": self.requested_count,
            "returned_count": self.returned_count,
            "covered_count": self.covered_count,
            "missing_count": len(self.missing_codes),
            "duplicate_count": len(self.duplicate_codes),
            "page_count": self.page_count,
            "coverage": round(self.coverage, 6),
            "status": self.status,
            "quality_reasons": list(self.quality_reasons),
            "started_at": self.started_at.isoformat(),
            "received_at": self.received_at.isoformat(),
            "finished_at": self.finished_at.isoformat(),
            "elapsed_ms": self.elapsed_ms,
        }
