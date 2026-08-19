"""Level 2 活跃池：强制标的 + 全市场粗筛 Top-N。"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from ..models import Quote, QuoteValidity


@dataclass
class ActivePoolSnapshot:
    as_of: datetime
    codes: set[str] = field(default_factory=set)
    mandatory_codes: set[str] = field(default_factory=set)
    reasons: dict[str, list[str]] = field(default_factory=dict)
    scores: dict[str, float] = field(default_factory=dict)
    promoted: set[str] = field(default_factory=set)
    demoted: set[str] = field(default_factory=set)
    capacity_exceeded: bool = False

    def rows(self) -> list[dict]:
        return [
            {
                "code": code,
                "mandatory": code in self.mandatory_codes,
                "reasons": self.reasons.get(code, []),
                "score": self.scores.get(code),
                "as_of": self.as_of.isoformat(),
            }
            for code in sorted(self.codes)
        ]


class ActivePoolManager:
    def __init__(self, *, max_candidates: int = 30, min_residence_sec: int = 300):
        self.max_candidates = max(int(max_candidates), 1)
        self.min_residence = timedelta(seconds=max(int(min_residence_sec), 0))
        self._members: set[str] = set()
        self._entered_at: dict[str, datetime] = {}

    def reset(self) -> None:
        self._members.clear()
        self._entered_at.clear()

    def restore(self, rows: list[dict]) -> None:
        self._members = set()
        self._entered_at = {}
        for row in rows:
            code = str(row.get("code", ""))
            if not code:
                continue
            try:
                entered = datetime.fromisoformat(str(row.get("as_of", "")))
            except ValueError:
                continue
            self._members.add(code)
            self._entered_at[code] = entered

    def refresh(self, *, candidates: list, quotes: dict[str, Quote], positions: list,
                orders: list, unknown_codes: set[str] | None, now: datetime,
                excluded_codes: set[str] | None = None) -> ActivePoolSnapshot:
        reasons: dict[str, list[str]] = {}
        excluded = excluded_codes or set()
        mandatory: set[str] = set()
        for position in positions:
            if int(getattr(position, "qty", 0) or 0) > 0:
                code = str(position.code)
                mandatory.add(code)
                reasons.setdefault(code, []).append("POSITION")
        for order in orders:
            status = str(getattr(order, "status", ""))
            if status in {
                "1", "2", "3", "5", "6", "7", "10",
                "CREATED", "SUBMITTING", "SUBMITTED", "PARTIAL",
                "CANCEL_PENDING", "UNKNOWN",
            }:
                code = str(order.code)
                mandatory.add(code)
                reasons.setdefault(code, []).append("ACTIVE_ORDER")
        for code in unknown_codes or set():
            mandatory.add(str(code))
            reasons.setdefault(str(code), []).append("UNKNOWN_ORDER")
        for code in mandatory & excluded:
            reasons.setdefault(code, []).append("DATA_DEGRADED_MANDATORY")

        scores: dict[str, float] = {}
        candidate_codes = {str(item.stock_code) for item in candidates}
        for code, quote in quotes.items():
            if code in excluded or code not in candidate_codes or quote.validity != QuoteValidity.VALID:
                continue
            amount = max(float(quote.amount or 0.0), 0.0)
            volume = max(float(quote.volume or 0.0), 0.0)
            pct = abs(float(quote.pct_chg or 0.0))
            # 确定性粗筛分：成交额为主，活跃度为辅；只负责晋级 L2，
            # 不能直接作为买入信号。
            scores[code] = amount / 100_000_000 + volume / 100_000_000 + min(pct, 20.0) / 20.0

        ranked = sorted(
            (code for code in scores if code not in mandatory),
            key=lambda code: (-scores[code], code),
        )
        candidate_slots = max(self.max_candidates - len(mandatory), 0)
        selected = set(ranked[:candidate_slots])
        for code in selected:
            reasons.setdefault(code, []).append("L1_TOP_N")

        desired = mandatory | selected
        # 非强制成员满足最短驻留，减少频繁重新预热。
        for code in self._members - desired:
            entered = self._entered_at.get(code, now)
            if code not in excluded and now - entered < self.min_residence:
                desired.add(code)
                reasons.setdefault(code, []).append("MIN_RESIDENCE")

        promoted = desired - self._members
        demoted = self._members - desired
        for code in promoted:
            self._entered_at[code] = now
        for code in demoted:
            self._entered_at.pop(code, None)
        self._members = desired
        return ActivePoolSnapshot(
            as_of=now,
            codes=set(desired),
            mandatory_codes=mandatory,
            reasons=reasons,
            scores=scores,
            promoted=promoted,
            demoted=demoted,
            capacity_exceeded=len(mandatory) > self.max_candidates,
        )
