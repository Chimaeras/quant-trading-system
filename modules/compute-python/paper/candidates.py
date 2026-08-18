"""候选加载：Java T1 接口为主，本地文件兜底；空候选是成功 NOOP。

P1-07：严格校验（6 位代码/交易所映射/去重/数量上限）；
T1 失败返回 t1_unavailable（dry-run 降级、confirm 阻断），
T1 成功但无候选返回 t1_no_candidate，两者不得混同。
"""
from __future__ import annotations

import json
from pathlib import Path

import requests

from .models import Candidate

_T1_TIMEOUT_SEC = 15


class CandidateValidationError(ValueError):
    """候选输入非法（代码/交易所/形状）。"""


def _infer_exchange(code: str) -> str:
    """按代码首位推断市场（6 沪 / 0、3 深）。"""
    return "SH" if code.startswith(("6", "9", "5")) else "SZ"


def _validated_code(code) -> str:
    s = str(code).strip()
    if not (len(s) == 6 and s.isdigit()):
        raise CandidateValidationError(f"证券代码必须为 6 位数字: {s!r}")
    if s[0] not in "0356":
        raise CandidateValidationError(f"证券代码首位无法映射交易所: {s!r}")
    return s


def _validate_exchange(exchange: str, code: str) -> str:
    ex = str(exchange).strip().upper()
    if ex not in ("SH", "SZ"):
        raise CandidateValidationError(f"交易所非法: {exchange!r}")
    expected = _infer_exchange(code)
    if ex != expected:
        raise CandidateValidationError(f"交易所 {ex} 与代码 {code} 应属 {expected} 不符")
    return ex


def _build_candidate(item: dict, *, signal_date: str, source: str) -> Candidate:
    if not isinstance(item, dict):
        raise CandidateValidationError(f"候选条目不是对象: {item!r}")
    code = _validated_code(item.get("stock_code"))
    name = str(item.get("stock_name", "")).strip()
    if not name:
        raise CandidateValidationError(f"候选 {code} 名称 stock_name 为空")
    exchange = _validate_exchange(item.get("exchange_type", _infer_exchange(code)), code)
    return Candidate(
        stock_code=code,
        stock_name=name,
        exchange_type=exchange,
        score=item.get("score"),
        rule_score=item.get("rule_score"),
        probability=item.get("probability"),
        close=item.get("close"),
        buyability=str(item.get("buyability", "")),
        risk_tags=item.get("risk_tags", []),
        is_limit_up=bool(item.get("is_limit_up", False)),
        lianban_days=int(item.get("lianban_days", 0)),
        signal_date=signal_date,
        source=source,
    )


def dedupe_candidates(candidates: list[Candidate]) -> tuple[list[Candidate], list[str]]:
    """按代码去重（保留首个，规则可审计），返回 (去重后, 被丢弃的重复代码)。"""
    seen: dict[str, Candidate] = {}
    dropped: list[str] = []
    for cand in candidates:
        if cand.stock_code in seen:
            dropped.append(cand.stock_code)
        else:
            seen[cand.stock_code] = cand
    return list(seen.values()), dropped


def load_from_file(path: str | Path, *, source: str = "file") -> list[Candidate]:
    """从 PAPER_CANDIDATE_FILE 兜底文件加载（最小形状见开发文档 §9.2）。"""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    items = data.get("candidates", [])
    if not isinstance(items, list):
        raise CandidateValidationError("candidates 必须是列表")
    signal_date = str(data.get("signal_date", ""))
    out = [_build_candidate(item, signal_date=signal_date, source=source) for item in items]
    out, _dropped = dedupe_candidates(out)
    return out


def load_from_t1(t1_base: str, signal_date: str, token: str = "", timeout: int = _T1_TIMEOUT_SEC) -> list[Candidate]:
    """GET /api/research/t1/candidates/{date}（Java 主输入）。

    Raises:
        requests.RequestException / KeyError / ValueError: 网络或结构异常，由 runner 决定兜底。
    """
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    resp = requests.get(f"{t1_base.rstrip('/')}/api/research/t1/candidates/{signal_date}", headers=headers, timeout=timeout)
    resp.raise_for_status()
    body = resp.json()
    # ApiResponse 包络: {code, message, data}; data 内含 candidatesJson 字符串或列表
    data = body.get("data") if isinstance(body, dict) else body
    if data is None:
        raise ValueError(f"T1 响应无 data: {body}")
    raw = data.get("candidatesJson", data.get("candidates"))
    if isinstance(raw, str):
        raw = json.loads(raw)
    if data.get("status") == "NO_ELIGIBLE_CANDIDATE" or not raw:
        return []
    out = [_build_candidate(item, signal_date=signal_date, source="t1") for item in raw]
    out, _dropped = dedupe_candidates(out)
    return out


def load_candidates(
    signal_date: str,
    *,
    candidate_file: str = "",
    t1_base: str = "",
    java_token: str = "",
    max_observe: int = 10,
) -> tuple[list[Candidate], str]:
    """加载入口：文件优先（显式指定时），否则 T1，再否则空。

    Returns:
        (候选列表, 来源标签)。
        - file / t1：来源成功（空列表代表正常无候选）。
        - t1_no_candidate：T1 成功但当日无符合条件候选。
        - t1_unavailable：T1 查询失败（dry-run 降级记录，confirm 必须 BLOCKED）。
        - t1_overflow：候选超过 max_observe 被截断（可审计）。
    """
    if candidate_file:
        cands = load_from_file(candidate_file)
        return cands[:max_observe], "file"
    if t1_base:
        try:
            cands = load_from_t1(t1_base, signal_date, java_token)
        except (requests.RequestException, KeyError, ValueError):
            # T1 不可用不阻塞 dry-run，返回空并让 runner 记录；confirm 阶段阻断
            return [], "t1_unavailable"
        if not cands:
            return [], "t1_no_candidate"
        if len(cands) > max_observe:
            return cands[:max_observe], "t1_overflow"
        return cands, "t1"
    return [], "none"
