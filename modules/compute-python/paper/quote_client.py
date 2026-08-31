"""行情客户端：/api/claw/query，固定问句模板，一标的一请求。"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime
import math
import time

import requests

from .config import PaperConfig
from .eastmoney_http import EASTMONEY_HEADERS, eastmoney_get
from .mx_http import MxHttpClient, raw_hash
from .mx_result import MxErrorKind, MxResult
from .parse_query import QuoteParseError, apply_freshness, parse_query
from .models import Quote, QuoteValidity

QUERY_ENDPOINT = "/api/claw/query"
# 全市场列表分页（易被截断/混入非目标代码）；L1 粗筛改走 ulist 按本地股票池 secids 批量拉取。
EASTMONEY_SNAPSHOT_ENDPOINT = "https://push2.eastmoney.com/api/qt/clist/get"
EASTMONEY_ULIST_ENDPOINT = "https://push2.eastmoney.com/api/qt/ulist.np/get"
# 主批偏小可显著降低东财整批 Connection reset；补拉再对半拆。
_EASTMONEY_ULIST_BATCH = 40
# 主扫描失败后的补拉：更小批次 + 额外轮次，专治整批 Connection reset。
_EASTMONEY_ULIST_BACKFILL_BATCH = 20
_EASTMONEY_ULIST_BACKFILL_ROUNDS = 2
# 兼容旧测试/外部引用
_EASTMONEY_HEADERS = EASTMONEY_HEADERS
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
    # not math.isfinite 同时拦截 NaN(inf) 与 ±inf：inf 通过 (number<0) 检查会漏网，
    # 作为价格/比例进入后续计算会把 prev_close、比例等推成 inf/NaN。
    if not math.isfinite(number) or (number < 0 and not allow_negative):
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
        # 上一轮仍有效的快照；本轮批量失败/漏票时在 freshness 窗口内兜底。
        self._last_good_quotes: dict[str, Quote] = {}

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

    def _request_ulist_payload(
        self,
        secids: str,
        *,
        fields: str,
        retries: int,
        backoff_base_sec: float = 0.5,
    ) -> tuple[dict | None, Exception | None]:
        """请求单批 ulist；返回 (payload, last_exc)。"""
        payload = None
        last_exc: Exception | None = None
        attempts = max(1, int(retries))
        for attempt in range(attempts):
            try:
                response = eastmoney_get(
                    EASTMONEY_ULIST_ENDPOINT,
                    params={
                        "fltt": "2",
                        "np": "1",
                        "ut": "bd1d9ddb04089700cf9c27f6f7426281",
                        "secids": secids,
                        "fields": fields,
                    },
                    timeout=self._config.quote_request_timeout_sec,
                )
                response.raise_for_status()
                payload = response.json()
                return payload if isinstance(payload, dict) else None, None
            except (requests.RequestException, ValueError) as exc:
                last_exc = exc
                if attempt < attempts - 1:
                    time.sleep(backoff_base_sec * (attempt + 1))
        return None, last_exc

    def _ingest_ulist_rows(
        self,
        rows: list,
        *,
        candidate_by_code: dict,
        result_by_code: dict[str, tuple[Quote | None, MxResult]],
        seen_codes: set[str],
        duplicate_codes: set[str],
        result: MxResult,
        batch_received_at: datetime,
    ) -> int:
        """把 ulist diff 行写入 result_by_code，返回新写入条数。"""
        ingested = 0
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
            if code not in result_by_code or result_by_code[code][0] is None:
                ingested += 1
            result_by_code[code] = (quote, result)
        return ingested

    def _pull_ulist_chunks(
        self,
        chunks: list[list[tuple[str, object]]],
        *,
        candidate_by_code: dict,
        result_by_code: dict[str, tuple[Quote | None, MxResult]],
        seen_codes: set[str],
        duplicate_codes: set[str],
        run_id: str,
        received_at: datetime,
        fields: str,
        batch_count_start: int,
        label: str,
        inter_batch_sleep_sec: float,
        retries: int,
        backoff_base_sec: float,
        workers: int = 1,
    ) -> tuple[int, int, MxResult | None]:
        """按 chunk 拉取 ulist。返回 (batch_count, failed_batches, terminal_error)。"""
        batch_count = batch_count_start
        failed_batches = 0
        terminal_error: MxResult | None = None
        save_raw = getattr(self._http, "save_raw", None)

        def request_chunk(item: tuple[int, list[tuple[str, object]]]):
            index, chunk = item
            if not chunk:
                return index, chunk, None, None
            secids = ",".join(
                f"{_eastmoney_market_id(code, getattr(candidate, 'exchange_type', None))}."
                f"{str(code).zfill(6)}"
                for code, candidate in chunk
            )
            payload, last_exc = self._request_ulist_payload(
                secids,
                fields=fields,
                retries=retries,
                backoff_base_sec=backoff_base_sec,
            )
            return index, chunk, payload, last_exc

        indexed_chunks = list(enumerate(chunks))
        if workers > 1 and len(indexed_chunks) > 1:
            with ThreadPoolExecutor(
                max_workers=min(workers, len(indexed_chunks)),
                thread_name_prefix="quote-batch",
            ) as executor:
                responses = list(executor.map(request_chunk, indexed_chunks))
        else:
            responses = [request_chunk(item) for item in indexed_chunks]

        for index, chunk, payload, last_exc in responses:
            if not chunk:
                continue
            batch_count += 1
            if not isinstance(payload, dict):
                failed_batches += 1
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
                # 单批失败不中断其余批次；末尾由补拉回收。
                if index + 1 < len(chunks):
                    time.sleep(max(inter_batch_sleep_sec, 0.2))
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
                failed_batches += 1
                terminal_error = MxResult(
                    endpoint=EASTMONEY_ULIST_ENDPOINT,
                    ok=False,
                    code=str(payload.get("rc", "PARSE_ERROR")),
                    message=f"批量行情业务失败 rc={payload.get('rc')}",
                    kind=MxErrorKind.BIZ_ERROR,
                    raw=payload,
                )
                if index + 1 < len(chunks):
                    time.sleep(max(inter_batch_sleep_sec, 0.2))
                continue

            raw_path = (
                save_raw(run_id, f"eastmoney_ulist_{label}_{batch_count}", payload)
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
            if received_at is not None and batch_received_at < received_at:
                batch_received_at = received_at
            self._ingest_ulist_rows(
                rows,
                candidate_by_code=candidate_by_code,
                result_by_code=result_by_code,
                seen_codes=seen_codes,
                duplicate_codes=duplicate_codes,
                result=result,
                batch_received_at=batch_received_at,
            )
            # 成功但明显少返回：也计为退化，交给后续补拉。
            returned_in_chunk = sum(
                1 for code, _ in chunk if code in result_by_code and result_by_code[code][0] is not None
            )
            if returned_in_chunk < len(chunk):
                failed_batches += 1

            if index + 1 < len(chunks) and inter_batch_sleep_sec > 0:
                time.sleep(inter_batch_sleep_sec)

        return batch_count, failed_batches, terminal_error

    @staticmethod
    def _chunk_items(items: list, size: int) -> list[list]:
        width = max(int(size), 1)
        return [items[offset: offset + width] for offset in range(0, len(items), width)]

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
        主扫描后对 MISSING 代码做小批次补拉，降低整批 Connection reset 导致的覆盖率空洞。
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
        duplicate_codes: set[str] = set()
        seen_codes: set[str] = set()
        terminal_error: MxResult | None = None
        batch_retries = max(
            1, min(self._config.max_retry, self._config.quote_batch_max_retry)
        )
        primary_retries = batch_retries

        primary_chunks = self._chunk_items(ordered, _EASTMONEY_ULIST_BATCH)
        batch_count, primary_failed, primary_error = self._pull_ulist_chunks(
            primary_chunks,
            candidate_by_code=candidate_by_code,
            result_by_code=result_by_code,
            seen_codes=seen_codes,
            duplicate_codes=duplicate_codes,
            run_id=run_id,
            received_at=current,
            fields=fields,
            batch_count_start=0,
            label="batch",
            inter_batch_sleep_sec=0.08,
            retries=primary_retries,
            backoff_base_sec=0.6,
            workers=self._config.quote_batch_workers,
        )
        if primary_error is not None:
            terminal_error = primary_error

        missing_after_primary = [
            code for code in candidate_by_code if code not in result_by_code
        ]
        # 如果主批次全部连接失败，说明是供应商/网络级故障；继续拆成 40/20
        # 只会重复等待超时，下一轮 60 秒扫描会自然重试，当前轮应尽快结束。
        primary_outage = bool(primary_chunks) and primary_failed >= len(primary_chunks) and not result_by_code
        backfill_round_limit = 0 if primary_outage else _EASTMONEY_ULIST_BACKFILL_ROUNDS
        backfill_recovered = 0
        backfill_rounds_ran = 0
        backfill_failed = 0
        # 对主扫描空洞做小批次补拉；批变小可显著降低东财掐连接概率。
        for round_idx in range(backfill_round_limit):
            missing = [
                (code, candidate_by_code[code])
                for code in candidate_by_code
                if code not in result_by_code
            ]
            if not missing:
                break
            backfill_rounds_ran += 1
            before = len(result_by_code)
            # 第二轮再对半分，进一步拆散顽固失败批。
            chunk_size = (
                _EASTMONEY_ULIST_BACKFILL_BATCH
                if round_idx == 0
                else max(_EASTMONEY_ULIST_BACKFILL_BATCH // 2, 10)
            )
            time.sleep(0.25 * (round_idx + 1))
            batch_count, round_failed, round_error = self._pull_ulist_chunks(
                self._chunk_items(missing, chunk_size),
                candidate_by_code=candidate_by_code,
                result_by_code=result_by_code,
                seen_codes=seen_codes,
                duplicate_codes=duplicate_codes,
                run_id=run_id,
                received_at=current,
                fields=fields,
                batch_count_start=batch_count,
                label=f"backfill{round_idx + 1}",
                inter_batch_sleep_sec=0.2 + 0.1 * round_idx,
                retries=batch_retries,
                backoff_base_sec=0.8,
                workers=self._config.quote_batch_workers,
            )
            backfill_failed += round_failed
            backfill_recovered += max(len(result_by_code) - before, 0)
            if round_error is not None:
                terminal_error = round_error

        for code in candidate_by_code:
            if code in result_by_code:
                continue
            result_by_code[code] = (
                None,
                terminal_error or MxResult(
                    endpoint=EASTMONEY_ULIST_ENDPOINT,
                    ok=False,
                    code="MISSING",
                    message=f"secids 分批+补拉完成后仍未返回 {code}",
                    kind=MxErrorKind.BIZ_ERROR,
                ),
            )

        cache_fill_count = self._apply_last_good_cache(
            result_by_code,
            candidate_codes=set(candidate_by_code),
            received_at=current,
        )
        # 仅保留本轮候选的缓存，避免池外陈旧条目无限膨胀。
        self._last_good_quotes = {
            code: quote
            for code, quote in self._last_good_quotes.items()
            if code in candidate_by_code
        }

        self.last_batch_meta = {
            "page_count": batch_count,
            "market_total": len(seen_codes),
            "seen_market_count": len(seen_codes),
            "duplicate_codes": sorted(duplicate_codes),
            "requested_count": len(candidate_by_code),
            "returned_count": sum(1 for quote, _ in result_by_code.values() if quote is not None),
            "terminal_error": getattr(terminal_error, "code", "") if terminal_error else "",
            "transport": "ulist_secids",
            "primary_failed_batches": primary_failed,
            "missing_after_primary": len(missing_after_primary),
            "backfill_rounds": backfill_rounds_ran,
            "backfill_recovered": backfill_recovered,
            "backfill_failed_batches": backfill_failed,
            "backfill_skipped_primary_outage": primary_outage,
            "cache_fill_count": cache_fill_count,
            "primary_batch_size": _EASTMONEY_ULIST_BATCH,
        }
        return result_by_code

    def _apply_last_good_cache(
        self,
        result_by_code: dict[str, tuple[Quote | None, MxResult]],
        *,
        candidate_codes: set[str],
        received_at: datetime,
    ) -> int:
        """本轮缺失/无效时，用上一轮仍新鲜的 VALID 快照兜底。"""
        filled = 0
        for code, (quote, result) in list(result_by_code.items()):
            if code not in candidate_codes:
                continue
            if quote is not None and quote.validity == QuoteValidity.VALID:
                self._last_good_quotes[code] = quote
                continue
            if quote is not None:
                # 有本轮解析结果（STALE/INVALID 等）时不覆盖，避免用更旧缓存掩盖源问题。
                continue
            cached = self._last_good_quotes.get(code)
            if cached is None or cached.market_time is None:
                continue
            fallback = replace(
                cached,
                received_at=received_at,
                source=f"{cached.source}+cache",
                error="",
                validity=QuoteValidity.VALID,
            )
            apply_freshness(
                fallback,
                self._config.quote_stale_sec,
                self._config.clock_skew_sec,
            )
            if fallback.validity != QuoteValidity.VALID:
                continue
            result_by_code[code] = (
                fallback,
                MxResult(
                    endpoint=EASTMONEY_ULIST_ENDPOINT,
                    ok=True,
                    code="CACHED",
                    message="使用上一轮有效快照兜底",
                    kind=MxErrorKind.OK,
                ),
            )
            filled += 1
        return filled
