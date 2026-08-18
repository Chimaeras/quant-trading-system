"""解析妙想 /api/claw/query 响应为 Quote。

真实抓包（2026-08-18）关键事实：
- 一次问句可能返回多张表：dataTableDTOList[] 里既有实时行情表（dataTypeEnum=HQ，
  指标 ZXJ_最新价/ZDF_涨跌幅/CJL_成交量/LB_量比），也有日线数据浏览器表
  （DATA_BROWSER，指标为收盘价）。必须选取 nameMap 含「最新价」的表。
- 指标编码不固定（ZXJ_f2_3 或纯数字），只能用 nameMap/field.returnName 按中文名匹配。
- 涨跌幅有三种格式：0.38 / 0.0038 / 0.38%；成交量带单位（387.2万）。
"""
from __future__ import annotations

from datetime import datetime

from .models import Quote, QuoteValidity

# 指标中文名 → Quote 字段
_INDICATOR_NAMES = {
    "最新价": "last",
    "涨跌幅": "pct_chg",
    "成交量": "volume",
    "量比": "volume_ratio",
}


class QuoteParseError(Exception):
    """行情解析失败（fail-closed，绝不猜字段）。"""


def parse_pct_chg(raw: str) -> float:
    """涨跌幅归一化为百分数：0.38 / 0.0038 / 0.38% → 0.38。

    判定规则：带 % 直接取数值；|值|<=1 且为纯小数时无法仅凭数值区分 0.38% 与
    0.0038%，以抓包为准（rawTable 中为百分数形式），此处约定输入为百分数。
    """
    s = str(raw).strip().replace("%", "").replace("％", "")
    if not s or s in ("--", "-", "nan"):
        raise QuoteParseError(f"涨跌幅无法解析: {raw!r}")
    value = float(s)
    # 纯小数且绝对值极小（如 0.0038）视为比例形式，转百分数
    if abs(value) < 0.01 and value != 0:
        return value * 100
    return value


def parse_volume(raw: str) -> float:
    """成交量归一化为股：387.2万 → 3872000；3872283 → 3872283。"""
    s = str(raw).strip().replace(",", "")
    if not s or s in ("--", "-"):
        raise QuoteParseError(f"成交量无法解析: {raw!r}")
    multiplier = 1.0
    if s.endswith(("万", "万股")):
        multiplier, s = 1e4, s.rstrip("万股")
    elif s.endswith(("亿", "亿股")):
        multiplier, s = 1e8, s.rstrip("亿股")
    elif s.endswith("股"):
        s = s.rstrip("股")
    return float(s) * multiplier


def parse_market_time(head: list) -> datetime | None:
    """headName 首元素为行情时间，如 '2026-08-18 16:30' 或 '2026-08-18(日)'。"""
    if not head:
        return None
    s = str(head[0]).strip()
    # 去掉星期后缀 2026-08-18(日)
    if "(" in s:
        s = s.split("(", 1)[0]
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


def _pick_indicator(table: dict, name_map: dict, code_map: dict, field_name: str) -> str | None:
    """按中文名取该指标列最后一个非空值。

    优先 rawTable（数值形），回退 table（展示形）。code_map 是 rawTable 的编码集。
    """
    codes = [c for c, name in name_map.items() if name == field_name and c in code_map]
    if not codes:
        codes = [c for c, name in name_map.items() if name == field_name and c in table]
        source = table
    else:
        source = code_map
    for code in codes:
        values = [v for v in source.get(code, []) if v not in (None, "", "--")]
        if values:
            return str(values[-1])
    return None


def parse_query(payload: dict, code: str, *, received_at: datetime | None = None) -> Quote:
    """解析 query 成功响应（顶层 status==0 已由 MxResult 判定）为 Quote。

    Raises:
        QuoteParseError: 结构不符、缺少最新价、代码不对齐等。
    """
    received = received_at or datetime.now()
    try:
        tables = payload["data"]["data"]["searchDataResultDTO"]["dataTableDTOList"]
    except (KeyError, TypeError) as exc:
        raise QuoteParseError(f"query 响应缺少 searchDataResultDTO 路径: {exc}") from exc
    if not tables:
        raise QuoteParseError("query 返回空表")

    # 选取 nameMap 含「最新价」的表（跳过日线 DATA_BROWSER 表）
    target = None
    for tbl in tables:
        name_map = tbl.get("nameMap", {}) or {}
        if any(name == "最新价" for name in name_map.values()):
            target = tbl
            break
    if target is None:
        raise QuoteParseError("所有表均不含「最新价」指标")

    tag = target.get("entityTagDTO") or {}
    secu_code = str(tag.get("secuCode", ""))
    if secu_code != code:
        raise QuoteParseError(f"返回代码 {secu_code!r} 与请求 {code!r} 不对齐")

    table = target.get("table", {}) or {}
    raw_table = target.get("rawTable", {}) or {}
    name_map = target.get("nameMap", {}) or {}

    last_raw = _pick_indicator(table, name_map, raw_table, "最新价")
    if last_raw is None:
        raise QuoteParseError("最新价列为空")
    try:
        last = float(str(last_raw).replace(",", "").rstrip("元"))
    except ValueError as exc:
        raise QuoteParseError(f"最新价无法解析: {last_raw!r}") from exc
    if last <= 0:
        raise QuoteParseError(f"最新价非正数: {last}")

    quote = Quote(
        code=code,
        name=str(tag.get("fullName", "")),
        exchange="SH" if str(tag.get("marketChar")) == ".SH" else "SZ",
        last=last,
        market_time=parse_market_time(table.get("headName", [])),
        received_at=received,
        raw_hash="",
    )

    for indicator, attr in (
        ("涨跌幅", "pct_chg"),
        ("成交量", "volume"),
        ("量比", "volume_ratio"),
    ):
        raw = _pick_indicator(table, name_map, raw_table, indicator)
        if raw is None:
            continue
        try:
            if attr == "pct_chg":
                quote.pct_chg = parse_pct_chg(raw)
            elif attr == "volume":
                quote.volume = parse_volume(raw)
            else:
                quote.volume_ratio = float(str(raw).replace(",", ""))
        except (ValueError, QuoteParseError):
            # 非核心指标解析失败不致命，保持 None
            continue

    # 成交量为 0 视为不可交易
    if quote.volume == 0:
        quote.validity = QuoteValidity.NOT_TRADABLE
    return quote


def apply_freshness(quote: Quote, stale_sec: int) -> Quote:
    """按本地时钟计算 freshness；行情时间缺失记 INVALID（文档 §8.3）。"""
    if quote.received_at is None or quote.market_time is None:
        quote.validity = QuoteValidity.INVALID if quote.validity == QuoteValidity.VALID else quote.validity
        quote.freshness_seconds = None
        return quote
    delta = (quote.received_at - quote.market_time).total_seconds()
    quote.freshness_seconds = max(delta, 0.0)
    if quote.validity == QuoteValidity.VALID and delta > stale_sec:
        quote.validity = QuoteValidity.STALE
    return quote
