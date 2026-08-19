"""行情客户端：/api/claw/query，固定问句模板，一标的一请求。"""
from __future__ import annotations

from datetime import datetime

from .config import PaperConfig
from .mx_http import MxHttpClient, raw_hash
from .mx_result import MxErrorKind, MxResult
from .parse_query import QuoteParseError, apply_freshness, parse_query
from .models import Quote

QUERY_ENDPOINT = "/api/claw/query"


def build_tool_query(stock_name: str, stock_code: str) -> str:
    """固定问句模板，禁止 LLM 自由发挥。"""
    return (
        f"{stock_name}({stock_code}) 最新价 涨跌幅 成交量 量比 "
        f"涨停价 跌停价"
    )


class QuoteClient:
    """行情查询客户端（只取数，不做任何业务判断）。"""

    def __init__(self, http: MxHttpClient, config: PaperConfig):
        self._http = http
        self._config = config

    def snapshot(
        self,
        stock_code: str,
        stock_name: str = "",
        *,
        run_id: str = "adhoc",
        received_at: datetime | None = None,
    ) -> tuple[Quote | None, MxResult]:
        """查单只标快照；返回 (Quote 或 None, MxResult)。失败只影响该票。"""
        body = {"toolQuery": build_tool_query(stock_name or stock_code, stock_code)}
        result = self._http.post(QUERY_ENDPOINT, body, retryable=True, run_id=run_id)
        if not result.ok:
            return None, result
        try:
            quote = parse_query(result.raw, stock_code, received_at=received_at)
        except QuoteParseError as exc:
            result.kind = MxErrorKind.PARSE_ERROR
            result.message = f"行情解析失败({stock_code}): {exc}"
            return None, result
        quote.raw_hash = raw_hash(result.raw)
        quote.raw_path = result.raw_path
        apply_freshness(quote, self._config.quote_stale_sec, self._config.clock_skew_sec)
        return quote, result
