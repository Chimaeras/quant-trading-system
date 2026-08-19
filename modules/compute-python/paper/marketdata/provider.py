"""行情供应商接口及现有 QuoteClient 的兼容路由器。"""
from __future__ import annotations

import secrets
from datetime import datetime
from typing import Protocol

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
        started = datetime.now()
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
        finished = datetime.now()
        status = "OK" if not missing and not duplicates else "DEGRADED"
        return BatchQuoteResult(
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
        )

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
            checked_at=datetime.now(),
            message="公开 HTTP 全市场扫描 + 妙想执行级查询；不声明盘口 SLA",
            recommended_interval_sec=60,
        )
