"""行情客户端：/api/claw/query，固定问句模板，一标的一请求。"""
from __future__ import annotations

from datetime import datetime
import time

import requests

from .config import PaperConfig
from .mx_http import MxHttpClient, raw_hash
from .mx_result import MxErrorKind, MxResult
from .parse_query import QuoteParseError, apply_freshness, parse_query
from .models import Quote

QUERY_ENDPOINT = "/api/claw/query"
EASTMONEY_SNAPSHOT_ENDPOINT = "https://push2.eastmoney.com/api/qt/clist/get"


def _eastmoney_market_id(code: str, exchange: str | None = None) -> str:
    ex = str(exchange or "").strip().upper()
    if ex in {"SH", "SSE"}:
        return "1"
    if ex in {"SZ", "SZSE"}:
        return "0"
    if ex in {"BJ", "BSE", "北交所"}:
        return "0"
    pure = str(code).strip().upper().split(".", 1)[0]
    if pure.startswith(("4", "8", "92")):
        return "0"
    return "1" if pure.startswith(("5", "6", "9")) else "0"


def _float_field(value, *, scale: float = 1.0) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number or number < 0:
        return None
    return number * scale


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

    def snapshot_many(
        self,
        candidates: list,
        *,
        run_id: str = "adhoc",
        received_at: datetime | None = None,
    ) -> dict[str, tuple[Quote | None, MxResult]]:
        """批量读取全市场快照，避免全量模式逐只消耗妙想 query 配额。

        东方财富批量接口只负责快速筛选所需的价格、涨跌幅、成交量和名称；
        它不作为下单限价依据。产生买卖信号后，WatchLoop 会再调用妙想
        `/query` 获取涨跌停价等权威执行字段。
        """
        current = received_at or datetime.now()
        result_by_code: dict[str, tuple[Quote | None, MxResult]] = {}
        candidate_by_code = {str(item.stock_code): item for item in candidates}
        codes = list(candidate_by_code)
        if not codes:
            return result_by_code

        fields = "f2,f3,f5,f6,f12,f13,f14,f15,f16,f17,f18"
        for offset in range(0, len(codes), 1000):
            chunk = codes[offset:offset + 1000]
            secids = ",".join(
                f"{_eastmoney_market_id(candidate_by_code[code].stock_code, candidate_by_code[code].exchange_type)}.{code}"
                for code in chunk
            )
            payload = None
            last_exc: Exception | None = None
            for attempt in range(max(1, self._config.max_retry)):
                try:
                    response = requests.get(
                        EASTMONEY_SNAPSHOT_ENDPOINT,
                        params={
                            "pn": "1",
                            "pz": str(len(chunk)),
                            "po": "1",
                            "np": "1",
                            "fltt": "2",
                            "invt": "2",
                            "fid": "f3",
                            "fs": "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:1+t:80",
                            "fields": fields,
                            "secids": secids,
                        },
                        headers={"User-Agent": "quant-trading-system/1.0"},
                        timeout=20,
                    )
                    response.raise_for_status()
                    payload = response.json()
                    break
                except (requests.RequestException, ValueError) as exc:
                    last_exc = exc
                    if attempt < max(1, self._config.max_retry) - 1:
                        time.sleep(0.5 * (attempt + 1))
            if not isinstance(payload, dict):
                result = MxResult(
                    endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                    ok=False,
                    code="HTTP_ERROR" if last_exc else "PARSE_ERROR",
                    message=(
                        f"批量行情请求失败: {type(last_exc).__name__}"
                        if last_exc else "批量行情响应不是对象"
                    ),
                    kind=MxErrorKind.HTTP_ERROR if last_exc else MxErrorKind.PARSE_ERROR,
                )
                for code in chunk:
                    result_by_code[code] = (None, result)
                continue

            data = payload.get("data")
            raw_diff = data.get("diff") if isinstance(data, dict) else None
            if isinstance(raw_diff, dict):
                rows = list(raw_diff.values())
            elif isinstance(raw_diff, list):
                rows = raw_diff
            else:
                rows = []
            if not rows:
                result = MxResult(
                    endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                    ok=False,
                    code="PARSE_ERROR",
                    message="批量行情响应缺少 data.diff",
                    kind=MxErrorKind.PARSE_ERROR,
                    raw=payload,
                )
                for code in chunk:
                    result_by_code[code] = (None, result)
                continue

            save_raw = getattr(self._http, "save_raw", None)
            raw_path = save_raw(run_id, "eastmoney_snapshot_batch", payload) if callable(save_raw) else ""
            result = MxResult(
                endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                ok=True,
                code="0",
                message="ok",
                data=data,
                kind=MxErrorKind.OK,
                raw=payload,
                raw_path=raw_path,
            )
            returned: set[str] = set()
            for row in rows:
                if not isinstance(row, dict):
                    continue
                code = str(row.get("f12", "")).strip().zfill(6)
                if code not in candidate_by_code:
                    continue
                candidate = candidate_by_code[code]
                last = _float_field(row.get("f2"))
                pct = _float_field(row.get("f3"))
                volume = _float_field(row.get("f5"), scale=100.0)
                amount = _float_field(row.get("f6"))
                name = str(row.get("f14", "")).strip() or candidate.stock_name
                if last is None or last <= 0:
                    quote = Quote(
                        code=code, name=name, exchange=candidate.exchange_type,
                        last=0.0, pct_chg=pct, volume=volume, amount=amount,
                        market_time=current, received_at=current,
                        source="eastmoney_batch", validity=QuoteValidity.INVALID,
                        error="LAST_PRICE_UNAVAILABLE",
                    )
                else:
                    validity = QuoteValidity.NOT_TRADABLE if not volume else QuoteValidity.VALID
                    quote = Quote(
                        code=code, name=name, exchange=candidate.exchange_type,
                        last=last, pct_chg=pct, volume=volume, amount=amount,
                        market_time=current, received_at=current,
                        source="eastmoney_batch", validity=validity,
                    )
                result_by_code[code] = (quote, result)
                returned.add(code)
            for code in chunk:
                if code not in returned:
                    result_by_code[code] = (
                        None,
                        MxResult(
                            endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                            ok=False,
                            code="MISSING",
                            message=f"批量行情未返回 {code}",
                            kind=MxErrorKind.BIZ_ERROR,
                            raw=payload,
                            raw_path=raw_path,
                        ),
                    )
        return result_by_code
