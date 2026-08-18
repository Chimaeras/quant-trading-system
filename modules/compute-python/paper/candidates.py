"""候选加载：Java T1 接口为主，本地文件兜底；空候选是成功 NOOP。"""
from __future__ import annotations

import json
from pathlib import Path

import requests

from .models import Candidate

_T1_TIMEOUT_SEC = 15


def load_from_file(path: str | Path, *, source: str = "file") -> list[Candidate]:
    """从 PAPER_CANDIDATE_FILE 兜底文件加载（最小形状见开发文档 §9.2）。"""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return [
        Candidate(
            stock_code=str(item["stock_code"]),
            stock_name=str(item.get("stock_name", "")),
            exchange_type=str(item.get("exchange_type", _infer_exchange(str(item["stock_code"])))),
            score=item.get("score"),
            rule_score=item.get("rule_score"),
            probability=item.get("probability"),
            close=item.get("close"),
            buyability=str(item.get("buyability", "")),
            risk_tags=item.get("risk_tags", []),
            is_limit_up=bool(item.get("is_limit_up", False)),
            lianban_days=int(item.get("lianban_days", 0)),
            signal_date=str(data.get("signal_date", "")),
            trade_date=str(data.get("trade_date", "")),
            source=source,
        )
        for item in data.get("candidates", [])
    ]


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
    out = []
    for item in raw:
        out.append(
            Candidate(
                stock_code=str(item["stock_code"]),
                stock_name=str(item.get("stock_name", "")),
                exchange_type=str(item.get("exchange_type", _infer_exchange(str(item["stock_code"])))),
                score=item.get("score"),
                rule_score=item.get("rule_score"),
                probability=item.get("probability"),
                close=item.get("close"),
                buyability=str(item.get("buyability", "")),
                risk_tags=item.get("risk_tags", []),
                is_limit_up=bool(item.get("is_limit_up", False)),
                lianban_days=int(item.get("lianban_days", 0)),
                signal_date=signal_date,
                source="t1",
            )
        )
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
        (候选列表, 来源标签)。空候选不算失败。
    """
    if candidate_file:
        cands = load_from_file(candidate_file)
        return cands[:max_observe], "file"
    if t1_base:
        try:
            cands = load_from_t1(t1_base, signal_date, java_token)
            return cands[:max_observe], "t1"
        except (requests.RequestException, KeyError, ValueError):
            # T1 不可用不阻塞 dry-run，返回空并让 runner 记录
            return [], "t1_unavailable"
    return [], "none"


def _infer_exchange(code: str) -> str:
    """按代码首位推断市场（6 沪 / 0、3 深）。"""
    return "SH" if code.startswith(("6", "9", "5")) else "SZ"
