"""行情供应商接口及现有 QuoteClient 的兼容路由器。"""
from __future__ import annotations

import secrets
from datetime import datetime
from typing import Protocol

from .. import clock
from ..models import Quote
from .models import BatchQuoteResult, MarketDataCapability, ProviderHealth


class MarketDataProvider(Protocol):
    name: str

    def capabilities(self) -> frozenset[MarketDataCapability]: ...
    def snapshot_all(self, candidates: list, *, run_id: str, received_at: datetime) -> BatchQuoteResult: ...
    def snapshot_execution(self, code: str, name: str, *, run_id: str,
                           received_at: datetime) -> tuple[Quote | None, object]: ...
    def health(self) -> ProviderHealth: ...


class LegacyMarketDataRouter:
    """把旧 QuoteClient 提升为显式 L1/L2 路由，不让 WatchLoop 判断源名称。"""

    name = "legacy_market_data_router"

    def __init__(self, quote_client):
        self._client = quote_client

    def capabilities(self) -> frozenset[MarketDataCapability]:
        caps = {MarketDataCapability.EXECUTION_QUOTE}
        if callable(getattr(self._client, "snapshot_many", None)):
            caps.add(MarketDataCapability.FULL_SNAPSHOT)
        return frozenset(caps)

    def snapshot_all(
        self, candidates: list, *, run_id: str, received_at: datetime
    ) -> BatchQuoteResult:
        started = clock.now()
        batch_id = f"{run_id}-l1-{secrets.token_hex(3)}"
        raw = self._client.snapshot_many(
            candidates, run_id=run_id, received_at=received_at
        )
        quotes: dict[str, Quote] = {}
        errors: dict[str, str] = {}
        for candidate in candidates:
            code = str(candidate.stock_code)
            quote, result = raw.get(code, (None, None))
            if quote is not None:
                quotes[code] = quote
            else:
                errors[code] = (
                    f"{getattr(result, 'code', 'NO_RESULT')}:"
                    f"{getattr(result, 'message', '行情未返回')}"
                )
        missing = [str(item.stock_code) for item in candidates if str(item.stock_code) not in quotes]
        meta = getattr(self._client, "last_batch_meta", {})
        duplicates = list(meta.get("duplicate_codes", [])) if isinstance(meta, dict) else []
        finished = clock.now()
        status = "OK" if not missing and not duplicates else "DEGRADED"
        quality_reasons: list[str] = []
        if isinstance(meta, dict):
            recovered = int(meta.get("backfill_recovered", 0) or 0)
            missing_primary = int(meta.get("missing_after_primary", 0) or 0)
            if missing_primary:
                quality_reasons.append(f"PRIMARY_MISSING:{missing_primary}")
            if recovered:
                quality_reasons.append(f"BACKFILL_RECOVERED:{recovered}")
        result = BatchQuoteResult(
            batch_id=batch_id,
            provider="eastmoney_batch",
            requested_count=len(candidates),
            started_at=started,
            received_at=received_at,
            finished_at=finished,
            quotes=quotes,
            errors=errors,
            missing_codes=missing,
            duplicate_codes=duplicates,
            page_count=(
                int(meta.get("page_count", 0) or 0)
                if isinstance(meta, dict)
                else (len(candidates) + 999) // 1000 if candidates else 0
            ),
            status=status,
            quality_reasons=quality_reasons,
        )
        return result

    def snapshot_execution(
        self, code: str, name: str, *, run_id: str, received_at: datetime
    ) -> tuple[Quote | None, object]:
        return self._client.snapshot(code, name, run_id=run_id, received_at=received_at)

    def health(self) -> ProviderHealth:
        caps = tuple(sorted(self.capabilities(), key=lambda item: item.value))
        return ProviderHealth(
            provider=self.name,
            ok=bool(caps),
            capabilities=caps,
            checked_at=clock.now(),
            message="公开 HTTP 批量轮询；仅外部 live 下单前补查妙想执行级行情；不声明盘口 SLA",
            recommended_interval_sec=60,
        )
