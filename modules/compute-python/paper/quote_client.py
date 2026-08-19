"""行情客户端：/api/claw/query，固定问句模板，一标的一请求。"""
from __future__ import annotations

from datetime import datetime
import time

import requests

from .config import PaperConfig
from .mx_http import MxHttpClient, raw_hash
from .mx_result import MxErrorKind, MxResult
from .parse_query import QuoteParseError, apply_freshness, parse_query
from .models import Quote, QuoteValidity

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


def _float_field(value, *, scale: float = 1.0, allow_negative: bool = False) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number or (number < 0 and not allow_negative):
        return None
    return number * scale


def _market_timestamp(value) -> datetime | None:
    """解析东方财富 f124 秒级时间戳；缺失时不以本地接收时间冒充行情时间。"""
    try:
        timestamp = int(float(value))
    except (TypeError, ValueError):
        return None
    if timestamp <= 0:
        return None
    try:
        return datetime.fromtimestamp(timestamp)
    except (OSError, OverflowError, ValueError):
        return None


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
        if not candidate_by_code:
            return result_by_code

        # clist/get 是市场列表分页接口，不保证按 secids 参数过滤。必须对
        # 沪深北 A 股市场做确定性分页后再与本地主数据求交，不能按候选代码
        # 分块反复请求第 1 页，否则会静默漏掉排序靠后的证券。
        fields = "f2,f3,f5,f6,f12,f13,f14,f15,f16,f17,f18,f124"
        page_size = 1000
        page = 1
        total: int | None = None
        seen_page_fingerprints: set[tuple[str, ...]] = set()
        terminal_error: MxResult | None = None
        while len(result_by_code) < len(candidate_by_code):
            payload = None
            last_exc: Exception | None = None
            for attempt in range(max(1, self._config.max_retry)):
                try:
                    response = requests.get(
                        EASTMONEY_SNAPSHOT_ENDPOINT,
                        params={
                            "pn": str(page),
                            "pz": str(page_size),
                            "po": "1",
                            "np": "1",
                            "fltt": "2",
                            "invt": "2",
                            # 代码排序使分页在同一轮请求期间更稳定；涨跌幅排序
                            # 会随行情变化造成跨页重复/遗漏。
                            "fid": "f12",
                            "fs": "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:1+t:80",
                            "fields": fields,
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
                terminal_error = MxResult(
                    endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                    ok=False,
                    code="HTTP_ERROR" if last_exc else "PARSE_ERROR",
                    message=(
                        f"批量行情请求失败: {type(last_exc).__name__}"
                        if last_exc else "批量行情响应不是对象"
                    ),
                    kind=MxErrorKind.HTTP_ERROR if last_exc else MxErrorKind.PARSE_ERROR,
                )
                break

            data = payload.get("data")
            raw_diff = data.get("diff") if isinstance(data, dict) else None
            if isinstance(raw_diff, dict):
                rows = list(raw_diff.values())
            elif isinstance(raw_diff, list):
                rows = raw_diff
            else:
                rows = []
            if total is None and isinstance(data, dict):
                try:
                    total = max(int(data.get("total", 0) or 0), 0)
                except (TypeError, ValueError):
                    total = 0
            if not rows:
                terminal_error = MxResult(
                    endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                    ok=False,
                    code="PARSE_ERROR",
                    message="批量行情响应缺少 data.diff",
                    kind=MxErrorKind.PARSE_ERROR,
                    raw=payload,
                )
                break

            save_raw = getattr(self._http, "save_raw", None)
            raw_path = save_raw(run_id, f"eastmoney_snapshot_batch_p{page}", payload) if callable(save_raw) else ""
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
            page_codes: list[str] = []
            for row in rows:
                if not isinstance(row, dict):
                    continue
                code = str(row.get("f12", "")).strip().zfill(6)
                page_codes.append(code)
                if code not in candidate_by_code:
                    continue
                candidate = candidate_by_code[code]
                last = _float_field(row.get("f2"))
                pct = _float_field(row.get("f3"), allow_negative=True)
                volume = _float_field(row.get("f5"), scale=100.0)
                amount = _float_field(row.get("f6"))
                name = str(row.get("f14", "")).strip() or candidate.stock_name
                market_time = _market_timestamp(row.get("f124"))
                if last is None or last <= 0:
                    quote = Quote(
                        code=code, name=name, exchange=candidate.exchange_type,
                        last=0.0, pct_chg=pct, volume=volume, amount=amount,
                        market_time=market_time, received_at=current,
                        source="eastmoney_batch", validity=QuoteValidity.INVALID,
                        error="LAST_PRICE_UNAVAILABLE",
                    )
                else:
                    validity = QuoteValidity.NOT_TRADABLE if not volume else QuoteValidity.VALID
                    error = ""
                    if market_time is None:
                        validity = QuoteValidity.INVALID
                        error = "MARKET_TIME_UNAVAILABLE"
                    quote = Quote(
                        code=code, name=name, exchange=candidate.exchange_type,
                        last=last, pct_chg=pct, volume=volume, amount=amount,
                        market_time=market_time, received_at=current,
                        source="eastmoney_batch", validity=validity, error=error,
                    )
                    if quote.validity == QuoteValidity.VALID:
                        apply_freshness(
                            quote,
                            self._config.quote_stale_sec,
                            self._config.clock_skew_sec,
                        )
                result_by_code[code] = (quote, result)
            fingerprint = tuple(page_codes)
            if fingerprint in seen_page_fingerprints:
                terminal_error = MxResult(
                    endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                    ok=False,
                    code="PAGINATION_REPEATED",
                    message=f"批量行情第 {page} 页与前页重复，停止以避免静默漏数",
                    kind=MxErrorKind.PARSE_ERROR,
                    raw=payload,
                    raw_path=raw_path,
                )
                break
            seen_page_fingerprints.add(fingerprint)
            if len(rows) < page_size or (total and page * page_size >= total):
                break
            page += 1

        for code in candidate_by_code:
            if code in result_by_code:
                continue
            result_by_code[code] = (
                None,
                terminal_error or MxResult(
                    endpoint=EASTMONEY_SNAPSHOT_ENDPOINT,
                    ok=False,
                    code="MISSING",
                    message=f"全市场分页完成后仍未返回 {code}",
                    kind=MxErrorKind.BIZ_ERROR,
                ),
            )
        return result_by_code
