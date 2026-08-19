"""盘中观察池加载：观察池文件优先，其次候选文件，最后 T1 只取代码。"""
from __future__ import annotations

import json
from pathlib import Path

import requests

from ..candidates import _infer_exchange, _validate_exchange, _validated_code, dedupe_candidates, load_candidates, load_from_file
from ..models import Candidate
from ..config import PaperConfig

_MARKET_LIST_PATH = "/api/market/stock/list"
_MARKET_LIST_PAGE_SIZE = 1000


def _market_exchange(value, code: str) -> str | None:
    """将 Java 主数据的 SSE/SZSE/BSE 映射为 watch 可执行的交易所。"""
    text = str(value or "").strip().upper()
    if text in {"SSE", "SH", "上交所"}:
        return "SH"
    if text in {"SZSE", "SZ", "深交所"}:
        return "SZ"
    if text in {"BSE", "BJ", "北交所"} or code.startswith(("4", "8", "9")):
        # 当前 broker_client / risk 的证券字段只支持沪深 A 股；北交所
        # 不应被悄悄映射到深市，明确记录为不支持并跳过。
        return None
    try:
        return _infer_exchange(code)
    except (TypeError, ValueError):
        return None


def load_full_market_universe(
    base_url: str,
    signal_date: str,
    token: str = "",
    *,
    max_codes: int = 10000,
    timeout: int = 15,
) -> tuple[list[Candidate], list[str]]:
    """读取 Java `/api/market/stock/list` 的全量沪深股票主数据。

    该接口返回代码和名称，避免使用只有代码的固定 universe 文件；分页拉取
    直到 total，且对超出上限、北交所和坏记录都留下可审计错误。
    """
    if not base_url:
        raise ValueError("FULL universe 需要配置 PAPER_T1_BASE/Java API 基址")
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    candidates: list[Candidate] = []
    errors: list[str] = []
    page = 1
    total: int | None = None
    while len(candidates) < max_codes:
        response = requests.get(
            f"{base_url.rstrip('/')}{_MARKET_LIST_PATH}",
            params={"page": page, "size": _MARKET_LIST_PAGE_SIZE},
            headers=headers,
            timeout=timeout,
        )
        response.raise_for_status()
        body = response.json()
        data = body.get("data") if isinstance(body, dict) else None
        if not isinstance(data, dict) or not isinstance(data.get("list"), list):
            raise ValueError("股票主数据响应缺少 data.list")
        rows = data["list"]
        if total is None:
            try:
                total = int(data.get("total", 0) or 0)
            except (TypeError, ValueError):
                total = 0
        if not rows:
            break
        for item in rows:
            if not isinstance(item, dict):
                errors.append("FULL_UNIVERSE_INVALID_ROW")
                continue
            try:
                code = _validated_code(item.get("stock_code", item.get("code")))
            except (TypeError, ValueError) as exc:
                errors.append(f"FULL_UNIVERSE_INVALID_CODE:{exc}")
                continue
            exchange = _market_exchange(item.get("exchange_type"), code)
            if exchange is None:
                errors.append(f"{code}:EXCHANGE_UNSUPPORTED")
                continue
            name = str(item.get("stock_name", item.get("name", ""))).strip()
            if not name:
                errors.append(f"{code}:STOCK_NAME_MISSING")
                continue
            candidates.append(Candidate(
                stock_code=code,
                stock_name=name,
                exchange_type=exchange,
                signal_date=signal_date,
                source="full_market",
            ))
            if len(candidates) >= max_codes:
                break
        if len(rows) < _MARKET_LIST_PAGE_SIZE or (total and page * _MARKET_LIST_PAGE_SIZE >= total):
            break
        page += 1
    if total and total > max_codes:
        errors.append(f"FULL_UNIVERSE_TRUNCATED:{total}>{max_codes}")
    candidates, duplicates = dedupe_candidates(candidates)
    if duplicates:
        errors.append("DUPLICATE_CODES_DROPPED:" + ",".join(sorted(set(duplicates))))
    return candidates, errors


def _watchlist_item(item, signal_date: str) -> Candidate:
    if isinstance(item, str):
        code = _validated_code(item)
        name = code
        exchange = _infer_exchange(code)
    elif isinstance(item, dict):
        code = _validated_code(item.get("stock_code", item.get("code")))
        name = str(item.get("stock_name", item.get("name", ""))).strip() or code
        exchange = _validate_exchange(item.get("exchange_type", _infer_exchange(code)), code)
    else:
        raise ValueError(f"观察池条目非法: {item!r}")
    return Candidate(
        stock_code=code,
        stock_name=name,
        exchange_type=exchange,
        signal_date=signal_date,
        source="watchlist",
    )


def load_watch_universe(config: PaperConfig, signal_date: str, override=None) -> tuple[list[Candidate], str, list[str]]:
    errors: list[str] = []
    if override is not None:
        candidates = list(override)
        source = "override"
    elif config.watchlist_file:
        payload = json.loads(Path(config.watchlist_file).read_text(encoding="utf-8"))
        items = payload.get("codes", []) if isinstance(payload, dict) else []
        if not isinstance(items, list):
            raise ValueError("watchlist codes 必须是列表")
        candidates = [_watchlist_item(item, signal_date) for item in items]
        source = "watchlist"
    elif config.candidate_file:
        candidates = load_from_file(config.candidate_file, source="candidate_file")
        source = "candidate_file"
    elif config.watch_universe_mode == "FULL":
        try:
            candidates, full_errors = load_full_market_universe(
                config.t1_base,
                signal_date,
                config.java_token,
                max_codes=config.max_watch_universe_codes,
            )
            errors.extend(full_errors)
            source = "full_market"
        except (OSError, TypeError, ValueError, requests.RequestException) as exc:
            candidates = []
            source = "full_market_unavailable"
            errors.append(f"FULL_UNIVERSE_UNAVAILABLE:{type(exc).__name__}:{exc}")
    else:
        candidates, source = load_candidates(
            signal_date,
            candidate_file="",
            t1_base=config.t1_base,
            java_token=config.java_token,
            max_observe=config.max_observe_codes,
        )
        if source == "t1_unavailable":
            errors.append("T1_UNAVAILABLE")

    candidates, duplicates = dedupe_candidates(candidates)
    if duplicates:
        errors.append("DUPLICATE_CODES_DROPPED:" + ",".join(sorted(set(duplicates))))
    max_codes = (
        config.max_watch_universe_codes
        if source == "full_market"
        else config.max_observe_codes
    )
    if len(candidates) > max_codes:
        errors.append("FULL_UNIVERSE_TRUNCATED" if source == "full_market" else "WATCHLIST_TRUNCATED")
    filtered: list[Candidate] = []
    for candidate in candidates[:max_codes]:
        if source == "full_market":
            # FULL 模式要扫描全量沪深主板/创业板/科创板；板块是否允许
            # 下单由 risk 闸门决定，不能在 universe 阶段把它们从扫描中删掉。
            filtered.append(candidate)
            continue
        blocked = candidate.board_blocked(config.allow_chinext, config.allow_star)
        if blocked:
            errors.append(f"{candidate.stock_code}:{blocked}")
            continue
        filtered.append(candidate)
    return filtered, source, errors
