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
# 全市场列表分页（易被截断/混入非目标代码）；L1 粗筛改走 ulist 按本地股票池 secids 批量拉取。
EASTMONEY_SNAPSHOT_ENDPOINT = "https://push2.eastmoney.com/api/qt/clist/get"
EASTMONEY_ULIST_ENDPOINT = "https://push2.eastmoney.com/api/qt/ulist.np/get"
_EASTMONEY_ULIST_BATCH = 80
_EASTMONEY_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Referer": "https://quote.eastmoney.com/",
}
_EASTMONEY_PROXIES = {"http": None, "https": None}


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
        self.last_batch_meta: dict = {}

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
        """按本地股票池批量读取快照，避免全量模式逐只消耗妙想 query 配额。

        使用东方财富 ulist.np + secids 分批拉取：覆盖率对齐本地主数据，
        不再依赖 clist 市场列表分页（实测 pz=1000 常被截成 100 且易提前停页）。
        该快照负责监控轮询和 L1/L2 粗筛；只有外部 live 真正产生买卖
        意图时，WatchLoop 才会补查妙想 /query。
        """
        current = received_at or datetime.now()
        result_by_code: dict[str, tuple[Quote | None, MxResult]] = {}
        candidate_by_code = {str(item.stock_code): item for item in candidates}
        if not candidate_by_code:
            return result_by_code

        fields = "f2,f3,f5,f6,f12,f13,f14,f15,f16,f17,f18,f124"
        ordered = list(candidate_by_code.items())
        batch_count = 0
        duplicate_codes: set[str] = set()
        seen_codes: set[str] = set()
        terminal_error: MxResult | None = None

        for offset in range(0, len(ordered), _EASTMONEY_ULIST_BATCH):
            chunk = ordered[offset: offset + _EASTMONEY_ULIST_BATCH]
            secids = ",".join(
                f"{_eastmoney_market_id(code, getattr(candidate, 'exchange_type', None))}."
                f"{str(code).zfill(6)}"
                for code, candidate in chunk
            )
            payload = None
            last_exc: Exception | None = None
            for attempt in range(max(1, self._config.max_retry)):
                try:
                    response = requests.get(
                        EASTMONEY_ULIST_ENDPOINT,
                        params={
                            "fltt": "2",
                            "np": "1",
                            "ut": "bd1d9ddb04089700cf9c27f6f7426281",
                            "secids": secids,
                            "fields": fields,
                        },
                        headers=_EASTMONEY_HEADERS,
                        timeout=20,
                        proxies=_EASTMONEY_PROXIES,
                    )
                    response.raise_for_status()
                    payload = response.json()
                    break
                except (requests.RequestException, ValueError) as exc:
                    last_exc = exc
                    if attempt < max(1, self._config.max_retry) - 1:
                        time.sleep(0.5 * (attempt + 1))
            batch_count += 1
            if not isinstance(payload, dict):
                terminal_error = MxResult(
                    endpoint=EASTMONEY_ULIST_ENDPOINT,
                    ok=False,
                    code="HTTP_ERROR" if last_exc else "PARSE_ERROR",
                    message=(
                        f"批量行情请求失败: {type(last_exc).__name__}"
                        if last_exc else "批量行情响应不是对象"
                    ),
                    kind=MxErrorKind.HTTP_ERROR if last_exc else MxErrorKind.PARSE_ERROR,
                )
                # 单批失败不中断其余批次，尽量保住已有覆盖率。
                continue

            data = payload.get("data")
            raw_diff = data.get("diff") if isinstance(data, dict) else None
            if isinstance(raw_diff, dict):
                rows = list(raw_diff.values())
            elif isinstance(raw_diff, list):
                rows = raw_diff
            else:
                rows = []
            if not rows and str(payload.get("rc")) != "0":
                terminal_error = MxResult(
                    endpoint=EASTMONEY_ULIST_ENDPOINT,
                    ok=False,
                    code=str(payload.get("rc", "PARSE_ERROR")),
                    message=f"批量行情业务失败 rc={payload.get('rc')}",
                    kind=MxErrorKind.BIZ_ERROR,
                    raw=payload,
                )
                continue

            save_raw = getattr(self._http, "save_raw", None)
            raw_path = (
                save_raw(run_id, f"eastmoney_ulist_batch_{batch_count}", payload)
                if callable(save_raw) else ""
            )
            result = MxResult(
                endpoint=EASTMONEY_ULIST_ENDPOINT,
                ok=True,
                code="0",
                message="ok",
                data=data,
                kind=MxErrorKind.OK,
                raw=payload,
                raw_path=raw_path,
            )
            # 每批使用「本批到达时间」，避免整轮扫描耗时数分钟后，
            # 后半批 market_time 相对扫描起点被误判为 FUTURE_TIMESTAMP。
            batch_received_at = datetime.now()
            if current is not None and batch_received_at < current:
                batch_received_at = current
            for row in rows:
                if not isinstance(row, dict):
                    continue
                code = str(row.get("f12", "")).strip().zfill(6)
                if code in seen_codes:
                    duplicate_codes.add(code)
                seen_codes.add(code)
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
                        market_time=market_time, received_at=batch_received_at,
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
                        market_time=market_time, received_at=batch_received_at,
                        source="eastmoney_batch", validity=validity, error=error,
                    )
                    if quote.validity == QuoteValidity.VALID:
                        apply_freshness(
                            quote,
                            self._config.quote_stale_sec,
                            self._config.clock_skew_sec,
                        )
                result_by_code[code] = (quote, result)

            if offset + _EASTMONEY_ULIST_BATCH < len(ordered):
                time.sleep(0.05)

        for code in candidate_by_code:
            if code in result_by_code:
                continue
            result_by_code[code] = (
                None,
                terminal_error or MxResult(
                    endpoint=EASTMONEY_ULIST_ENDPOINT,
                    ok=False,
                    code="MISSING",
                    message=f"secids 分批完成后仍未返回 {code}",
                    kind=MxErrorKind.BIZ_ERROR,
                ),
            )
        self.last_batch_meta = {
            "page_count": batch_count,
            "market_total": len(seen_codes),
            "seen_market_count": len(seen_codes),
            "duplicate_codes": sorted(duplicate_codes),
            "requested_count": len(candidate_by_code),
            "returned_count": sum(1 for quote, _ in result_by_code.values() if quote is not None),
            "terminal_error": getattr(terminal_error, "code", "") if terminal_error else "",
            "transport": "ulist_secids",
        }
        return result_by_code
