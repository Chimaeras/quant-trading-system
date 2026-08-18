"""用妙想 query 拉取启动预热 K 线，并只返回已收盘 bar。"""
from __future__ import annotations

from datetime import datetime, timedelta

from ..config import PaperConfig
from ..mx_result import MxErrorKind, MxResult
from ..quote_client import QUERY_ENDPOINT
from .models import WatchBar


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


def _series(table: dict, name_map: dict, aliases: tuple[str, ...], *, volume: bool = False) -> list[float]:
    source = table.get("rawTable") or table.get("table") or {}
    for code, label in name_map.items():
        if any(alias in str(label) for alias in aliases):
            values = source.get(code, [])
            if isinstance(values, list):
                return [_number(value, volume=volume) for value in values]
    return []


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
        granularity = str(table.get("dateGranularity", "")).upper() if isinstance(table, dict) else ""
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
    """启动预热客户端。接口失败由调用方决定是否禁用该证券。"""

    def __init__(self, http, config: PaperConfig):
        self._http = http
        self._config = config

    def fetch_history(
        self,
        code: str,
        name: str,
        period_min: int,
        limit: int,
        *,
        run_id: str = "adhoc",
        as_of: datetime | None = None,
    ) -> tuple[list[WatchBar] | None, MxResult]:
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
            bars = parse_kline_payload(result.raw, code, period_min, as_of or datetime.now())
        except (TypeError, ValueError) as exc:
            result.ok = False
            result.kind = MxErrorKind.PARSE_ERROR
            result.message = f"K 线解析失败({code}): {exc}"
            return None, result
        return bars[-limit:], result
