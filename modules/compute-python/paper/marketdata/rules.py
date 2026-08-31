"""Java 证券交易规则快照客户端。"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP

import requests

from ..models import Quote


@dataclass(frozen=True)
class SecurityRule:
    code: str
    exchange: str
    board: str
    status: str
    is_st: bool
    tick_size: float | None
    price_decimals: int | None
    limit_rate: float | None
    buy_lot: int | None
    odd_lot_sell: bool | None
    settlement: str
    rule_version: str
    source: str
    reliability: str
    effective_date: str

    @classmethod
    def from_dict(cls, item: dict) -> "SecurityRule":
        return cls(
            code=str(item.get("stock_code", item.get("code", ""))).zfill(6),
            exchange=str(item.get("exchange_type", "")),
            board=str(item.get("board", "UNKNOWN")),
            status=str(item.get("status", "UNKNOWN")),
            is_st=bool(item.get("is_st", False)),
            tick_size=float(item["tick_size"]) if item.get("tick_size") is not None else None,
            price_decimals=int(item["price_decimals"]) if item.get("price_decimals") is not None else None,
            limit_rate=float(item["limit_rate"]) if item.get("limit_rate") is not None else None,
            buy_lot=int(item["buy_lot"]) if item.get("buy_lot") is not None else None,
            odd_lot_sell=item.get("odd_lot_sell"),
            settlement=str(item.get("settlement", "UNKNOWN")),
            rule_version=str(item.get("rule_version", "unknown")),
            source=str(item.get("source", "unknown")),
            reliability=str(item.get("reliability", "limited")),
            effective_date=str(item.get("effective_date", "")),
        )

    def to_dict(self) -> dict:
        return dict(self.__dict__)


class SecurityRuleClient:
    PATH = "/api/market/security-rules"

    def __init__(self, base_url: str, token: str = "", *, timeout: int = 15):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def fetch_all(self, effective_date: str, *, max_codes: int = 10000) -> tuple[dict[str, SecurityRule], list[str]]:
        if not self.base_url:
            return {}, ["SECURITY_RULE_BASE_URL_MISSING"]
        headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        rules: dict[str, SecurityRule] = {}
        errors: list[str] = []
        page = 1
        size = 1000
        while len(rules) < max_codes:
            response = requests.get(
                f"{self.base_url}{self.PATH}",
                params={"asOf": effective_date, "page": page, "size": size},
                headers=headers,
                timeout=self.timeout,
            )
            response.raise_for_status()
            body = response.json()
            data = body.get("data") if isinstance(body, dict) else None
            rows = data.get("list") if isinstance(data, dict) else None
            if not isinstance(rows, list):
                raise ValueError("证券规则响应缺少 data.list")
            for item in rows:
                try:
                    rule = SecurityRule.from_dict(item)
                    if len(rule.code) != 6 or not rule.code.isdigit() or rule.code == "000000":
                        raise ValueError(f"代码非法:{rule.code}")
                    rules[rule.code] = rule
                except (TypeError, ValueError) as exc:
                    errors.append(f"SECURITY_RULE_INVALID:{exc}")
            total = int(data.get("total", 0) or 0)
            if len(rows) < size or page * size >= total:
                break
            page += 1
        if len(rules) >= max_codes:
            errors.append("SECURITY_RULE_TRUNCATED")
        return rules, errors


def _round_half_up(value: float, ndigits: int) -> float:
    """A 股『四舍五入』取整到 ndigits 位小数（半价上舍入）。

    交易所涨跌停价按四舍五入取到最小价位；Python 内置 round 为银行家舍入，在精确 .005
    边界会舍到偶数（如 round(2.675, 2) → 2.67），与约定不一致。用 Decimal ROUND_HALF_UP
    在十进制表示上按半价上舍入。
    """
    q = Decimal(1).scaleb(-ndigits)
    return float(Decimal(str(value)).quantize(q, rounding=ROUND_HALF_UP))


def apply_rule_price_limits(quote: Quote, rule: SecurityRule | None) -> Quote:
    """仅在规则和前收推导输入都可靠时补齐涨跌停价，并保留来源。"""
    if rule is None or rule.limit_rate is None or quote.pct_chg is None:
        return quote
    if quote.last <= 0 or quote.pct_chg <= -100:
        return quote
    decimals = rule.price_decimals if rule.price_decimals is not None else 2
    previous_close = quote.last / (1 + quote.pct_chg / 100)
    if quote.limit_up_price is None:
        quote.limit_up_price = _round_half_up(previous_close * (1 + rule.limit_rate), decimals)
        quote.limit_up_source = f"security_rule:{rule.rule_version}:{rule.source}"
    if quote.limit_down_price is None:
        quote.limit_down_price = _round_half_up(previous_close * (1 - rule.limit_rate), decimals)
        quote.limit_down_source = f"security_rule:{rule.rule_version}:{rule.source}"
    return quote
