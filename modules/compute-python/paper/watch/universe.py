"""盘中观察池加载：观察池文件优先，其次候选文件，最后 T1 只取代码。"""
from __future__ import annotations

import json
from pathlib import Path

from ..candidates import _infer_exchange, _validate_exchange, _validated_code, dedupe_candidates, load_candidates, load_from_file
from ..models import Candidate
from ..config import PaperConfig


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
    if len(candidates) > config.max_observe_codes:
        errors.append("WATCHLIST_TRUNCATED")
    filtered: list[Candidate] = []
    for candidate in candidates[:config.max_observe_codes]:
        blocked = candidate.board_blocked(config.allow_chinext, config.allow_star)
        if blocked:
            errors.append(f"{candidate.stock_code}:{blocked}")
            continue
        filtered.append(candidate)
    return filtered, source, errors
