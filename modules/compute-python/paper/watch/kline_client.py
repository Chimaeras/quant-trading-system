"""用妙想 query 拉取启动预热 K 线，并只返回已收盘 bar。"""
from __future__ import annotations

from datetime import datetime, timedelta
import time

import requests

from ..config import PaperConfig
from ..mx_result import MxErrorKind, MxResult
from ..quote_client import QUERY_ENDPOINT
from .models import WatchBar


EASTMONEY_KLINE_ENDPOINT = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
_SUPPORTED_PERIODS = {1, 5, 15, 30, 60}
_EASTMONEY_PROXIES = {"http": None, "https": None}


def _number(value, *, volume: bool = False) -> float:
    text = str(value).strip().replace(",", "")
    if text in {"", "-", "--", "nan", "None"}:
        raise ValueError(f"数值为空: {value!r}")
    multiplier = 1.0
    if volume:
        if text.endswith("亿"):
            multiplier, text = 1e8, text[:-1]
        elif text.endswith("万"):
            multiplier, text = 1e4, text[:-1]
        elif text.endswith("股"):
            text = text[:-1]
    return float(text) * multiplier


def _parse_time(value) -> datetime:
    text = str(value).strip()
    if "(" in text:
        text = text.split("(", 1)[0]
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y/%m/%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    raise ValueError(f"K 线时间无法解析: {value!r}")


def _market_id(code: str, exchange: str | None = None) -> str:
    """东方财富 secid：沪市为 1，深市/北交所为 0。"""
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


def _parse_eastmoney_klines(payload: dict, code: str, period_min: int, as_of: datetime) -> list[WatchBar]:
    """解析东方财富公开 K 线接口；成交量 f56 为手，统一换算成股。"""
    data = payload.get("data") if isinstance(payload, dict) else None
    rows = data.get("klines") if isinstance(data, dict) else None
    if not isinstance(rows, list) or not rows:
        raise ValueError("东方财富 K 线响应缺少 klines")
    pure_code = str(code).strip().upper().split(".", 1)[0]
    returned_code = str(data.get("code", "")).strip().upper().split(".", 1)[0]
    if returned_code and returned_code != pure_code:
        raise ValueError(f"返回代码 {returned_code!r} 与请求 {pure_code!r} 不对齐")
    bars: list[WatchBar] = []
    for row in rows:
        parts = str(row).split(",")
        if len(parts) < 6:
            continue
        try:
            start = _parse_time(parts[0])
            open_price = _number(parts[1])
            close = _number(parts[2])
            high = _number(parts[3])
            low = _number(parts[4])
            volume = _number(parts[5]) * 100.0
        except (TypeError, ValueError):
            continue
        end = start + timedelta(minutes=period_min)
        if end > as_of or min(open_price, high, low, close) <= 0 or high < low:
            continue
        bars.append(WatchBar(
            code=pure_code,
            period_min=period_min,
            open=open_price,
            high=high,
            low=low,
            close=close,
            volume=volume,
            start_ts=start,
            end_ts=end,
            closed=True,
            source="eastmoney_kline",
        ))
    if not bars:
        raise ValueError("东方财富 K 线没有可用的已收盘 bar")
    dedup: dict[datetime, WatchBar] = {bar.start_ts: bar for bar in bars}
    return [dedup[key] for key in sorted(dedup)]


def _series(table: dict, name_map: dict, aliases: tuple[str, ...], *, volume: bool = False) -> list[float]:
    source = table.get("rawTable") or table.get("table") or {}
    for code, label in name_map.items():
        if any(alias in str(label) for alias in aliases):
            values = source.get(code, [])
            if isinstance(values, list):
                return [_number(value, volume=volume) for value in values]
    return []


def _minute_granularity(table: dict) -> str:
    """读取接口可能放在表级、field 或字段定义中的显式粒度。"""
    candidates: list[object] = [table.get("dateGranularity")]
    field = table.get("field")
    if isinstance(field, dict):
        candidates.append(field.get("dateGranularity"))
    field_set = table.get("fieldSet") or []
    if isinstance(field_set, list):
        candidates.extend(
            item.get("dateGranularity")
            for item in field_set
            if isinstance(item, dict)
        )
    for value in candidates:
        text = str(value or "").strip().upper()
        if text:
            return text
    return ""


def parse_kline_payload(payload: dict, code: str, period_min: int, as_of: datetime) -> list[WatchBar]:
    try:
        tables = payload["data"]["data"]["searchDataResultDTO"]["dataTableDTOList"]
    except (KeyError, TypeError) as exc:
        raise ValueError(f"K 线响应缺少 dataTableDTOList: {exc}") from exc
    if not isinstance(tables, list):
        raise ValueError("dataTableDTOList 不是列表")

    target = None
    for table in tables:
        name_map = table.get("nameMap", {}) if isinstance(table, dict) else {}
        granularity = _minute_granularity(table) if isinstance(table, dict) else ""
        labels = {str(value) for value in name_map.values()}
        # 必须显式声明分钟粒度；无 dateGranularity 的 DATA_BROWSER 表可能是日线，
        # 混入会把日线 close 拼进 5 分钟序列（spec §5.2 明令禁止）。
        if granularity in {"MIN", "MINUTE", "5MIN", "1MIN"} and (
            any(any(alias in label for alias in ("开盘价", "开盘")) for label in labels)
            and any(any(alias in label for alias in ("最高价", "最高")) for label in labels)
            and any(any(alias in label for alias in ("最低价", "最低")) for label in labels)
            and any(any(alias in label for alias in ("收盘价", "收盘")) for label in labels)
        ):
            target = table
            break
    if target is None:
        raise ValueError("没有找到包含 OHLC 的分钟 K 线表")

    # 返回代码必须与请求代码对齐，防止同名/模糊查询返回其他标的的 K 线。
    tag = target.get("entityTagDTO") or {}
    secu_code = str(tag.get("secuCode", ""))
    if secu_code and secu_code != code:
        raise ValueError(f"返回代码 {secu_code!r} 与请求 {code!r} 不对齐")

    name_map = target.get("nameMap", {}) or {}
    head = target.get("headName", []) or []
    if not isinstance(head, list) or not head:
        raise ValueError("K 线时间列为空")
    opens = _series(target, name_map, ("开盘价", "开盘"))
    highs = _series(target, name_map, ("最高价", "最高"))
    lows = _series(target, name_map, ("最低价", "最低"))
    closes = _series(target, name_map, ("收盘价", "收盘"))
    volumes = _series(target, name_map, ("成交量", "成交量(股)", "成交量（股）"), volume=True)
    length = min(len(head), len(opens), len(highs), len(lows), len(closes))
    if length == 0:
        raise ValueError("K 线 OHLC 数据为空")

    bars: list[WatchBar] = []
    for index in range(length):
        start = _parse_time(head[index])
        end = start + timedelta(minutes=period_min)
        if end > as_of:
            continue
        open_price, high, low, close = opens[index], highs[index], lows[index], closes[index]
        if min(open_price, high, low, close) <= 0 or high < low:
            continue
        volume = volumes[index] if index < len(volumes) else 0.0
        bars.append(WatchBar(
            code=code,
            period_min=period_min,
            open=open_price,
            high=high,
            low=low,
            close=close,
            volume=volume,
            start_ts=start,
            end_ts=end,
            closed=True,
            source="mx_query_kline",
        ))
    if not bars:
        raise ValueError("没有可用的已收盘 K 线")
    dedup: dict[datetime, WatchBar] = {bar.start_ts: bar for bar in bars}
    return [dedup[key] for key in sorted(dedup)]


class KlineClient:
    """启动预热客户端。

    妙想 query 的自然语言问句在实测中会把“5 分钟”降级为 DAY；默认使用
    东方财富官方公开 K 线接口获取分钟历史，仍由妙想 query 提供实时快照、
    账户和模拟交易。设置 PAPER_KLINE_SOURCE=mx_query 可保留旧路径。
    """

    def __init__(self, http, config: PaperConfig):
        self._http = http
        self._config = config

    def _fetch_eastmoney(
        self,
        code: str,
        period_min: int,
        limit: int,
        *,
        exchange: str | None,
        as_of: datetime,
        run_id: str,
    ) -> tuple[list[WatchBar] | None, MxResult]:
        if period_min not in _SUPPORTED_PERIODS:
            return None, MxResult(
                endpoint=EASTMONEY_KLINE_ENDPOINT,
                ok=False,
                code="CONFIG_ERROR",
                message=f"东方财富 K 线周期不支持: {period_min}",
                kind=MxErrorKind.BIZ_ERROR,
            )
        pure_code = str(code).strip().upper().split(".", 1)[0]
        # 只请求足够覆盖 warmup 的窗口，避免用 1990-2050 的超大响应触发网关/代理限制。
        history_days = max(20, min(90, int(limit * period_min / 240 * 2) + 10))
        begin = (as_of - timedelta(days=history_days)).strftime("%Y%m%d")
        end_date = (as_of + timedelta(days=1)).strftime("%Y%m%d")
        params = {
            "secid": f"{_market_id(pure_code, exchange)}.{pure_code}",
            "klt": str(period_min),
            "fqt": "0",  # 原始行情，不使用复权价格参与模拟执行
            "beg": begin,
            "end": end_date,
            "lmt": str(max(limit, 80)),
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
        }
        response = None
        last_exc: Exception | None = None
        for attempt in range(max(1, self._config.max_retry)):
            try:
                response = requests.get(
                    EASTMONEY_KLINE_ENDPOINT,
                    params=params,
                    headers={"User-Agent": "quant-trading-system/1.0"},
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
        else:
            payload = None
        if payload is None:
            exc = last_exc or ValueError("空响应")
            return None, MxResult(
                endpoint=EASTMONEY_KLINE_ENDPOINT,
                ok=False,
                code="HTTP_ERROR",
                message=f"东方财富 K 线请求失败: {type(exc).__name__}",
                kind=MxErrorKind.HTTP_ERROR,
            )
        if not isinstance(payload, dict):
            return None, MxResult(
                endpoint=EASTMONEY_KLINE_ENDPOINT,
                ok=False,
                code="PARSE_ERROR",
                message=f"东方财富 K 线响应不是 JSON 对象: {type(payload).__name__}",
                kind=MxErrorKind.PARSE_ERROR,
            )
        if str(payload.get("rc")) == "0":
            result = MxResult(
                endpoint=EASTMONEY_KLINE_ENDPOINT,
                ok=True,
                code="0",
                message="ok",
                data=payload.get("data"),
                kind=MxErrorKind.OK,
                raw=payload,
            )
        else:
            result = MxResult.from_json(EASTMONEY_KLINE_ENDPOINT, payload)
        save_raw = getattr(self._http, "save_raw", None)
        result.raw_path = save_raw(run_id, "eastmoney_kline", payload) if callable(save_raw) else ""
        if not result.ok:
            return None, result
        result.raw = payload
        try:
            bars = _parse_eastmoney_klines(payload, pure_code, period_min, as_of)
        except (TypeError, ValueError) as exc:
            result.ok = False
            result.kind = MxErrorKind.PARSE_ERROR
            result.message = f"东方财富 K 线解析失败({pure_code}): {exc}"
            return None, result
        return bars[-limit:], result

    def fetch_history(
        self,
        code: str,
        name: str,
        period_min: int,
        limit: int,
        *,
        run_id: str = "adhoc",
        as_of: datetime | None = None,
        exchange: str | None = None,
    ) -> tuple[list[WatchBar] | None, MxResult]:
        current = as_of or datetime.now()
        source = self._config.kline_source
        if source in {"eastmoney", "auto"}:
            bars, result = self._fetch_eastmoney(
                code,
                period_min,
                limit,
                exchange=exchange,
                as_of=current,
                run_id=run_id,
            )
            if bars is not None or source == "eastmoney":
                return bars, result
        query = f"{name or code}({code}) 近 {limit} 根 {period_min}分钟 开盘价 最高价 最低价 收盘价 成交量"
        result = self._http.post(
            QUERY_ENDPOINT,
            {"toolQuery": query},
            retryable=True,
            run_id=run_id,
        )
        if not result.ok:
            return None, result
        try:
            bars = parse_kline_payload(result.raw, code, period_min, current)
        except (TypeError, ValueError) as exc:
            result.ok = False
            result.kind = MxErrorKind.PARSE_ERROR
            result.message = f"K 线解析失败({code}): {exc}"
            return None, result
        return bars[-limit:], result
