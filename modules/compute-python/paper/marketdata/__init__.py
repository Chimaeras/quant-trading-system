"""实时行情数据平面：供应商适配、质量检测、活跃池和证券规则。"""

from .active_pool import ActivePoolManager, ActivePoolSnapshot
from .models import (
    BatchQuoteResult,
    MarketDataCapability,
    ProviderHealth,
    QuoteQuality,
)
from .provider import LegacyMarketDataRouter, MarketDataProvider
from .quality import MarketDataQualityEngine
from .rules import SecurityRule, SecurityRuleClient, apply_rule_price_limits

__all__ = [
    "ActivePoolManager",
    "ActivePoolSnapshot",
    "BatchQuoteResult",
    "LegacyMarketDataRouter",
    "MarketDataCapability",
    "MarketDataProvider",
    "MarketDataQualityEngine",
    "ProviderHealth",
    "QuoteQuality",
    "SecurityRule",
    "SecurityRuleClient",
    "apply_rule_price_limits",
]
