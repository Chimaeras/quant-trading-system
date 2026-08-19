from __future__ import annotations

from datetime import datetime

from paper.candidates import _infer_exchange, _validate_exchange, _validated_code
from paper.decide import derive_limit_up_price
from paper.models import Quote, QuoteValidity
from paper.parse_query import parse_query
from paper.quote_client import _eastmoney_market_id
from paper.watch.kline_client import _market_id
from paper.watch.universe import _market_exchange, load_full_market_universe


class _Response:
    def __init__(self, body: dict):
        self._body = body

    def raise_for_status(self):
        return None

    def json(self):
        return self._body


def test_bse_code_prefixes_are_mapped_without_breaking_shanghai_b_shares():
    assert _validated_code("920000") == "920000"
    assert _validated_code("830001") == "830001"
    assert _infer_exchange("920000") == "BJ"
    assert _infer_exchange("830001") == "BJ"
    assert _infer_exchange("900001") == "SH"
    assert _validate_exchange("BSE", "920000") == "BJ"
    assert _validate_exchange("BJ", "830001") == "BJ"


def test_market_exchange_rejects_inconsistent_backend_market():
    assert _market_exchange("BSE", "920000") == "BJ"
    assert _market_exchange(None, "920000") == "BJ"
    assert _market_exchange("SSE", "900001") == "SH"
    assert _market_exchange("SSE", "920000") is None


def test_eastmoney_market_ids_use_zero_for_bse():
    assert _eastmoney_market_id("920000", "BJ") == "0"
    assert _eastmoney_market_id("920000", "BSE") == "0"
    assert _eastmoney_market_id("920000") == "0"
    assert _eastmoney_market_id("900001") == "1"
    assert _market_id("920000", "BJ") == "0"
    assert _market_id("920000") == "0"


def test_query_parser_preserves_bse_market_tag():
    payload = {
        "data": {
            "data": {
                "searchDataResultDTO": {
                    "dataTableDTOList": [
                        {
                            "entityTagDTO": {
                                "secuCode": "920000",
                                "fullName": "示例新代码",
                                "marketChar": ".BJ",
                            },
                            "nameMap": {"last": "最新价"},
                            "table": {"headName": ["2026-08-19 10:00"]},
                            "rawTable": {"last": ["100.00"]},
                        }
                    ]
                }
            }
        }
    }

    quote = parse_query(payload, "920000")

    assert quote.exchange == "BJ"


def test_full_market_universe_keeps_bse_rows(monkeypatch):
    body = {
        "data": {
            "total": 4,
            "list": [
                {"stock_code": "600519", "stock_name": "贵州茅台", "exchange_type": "SSE"},
                {"stock_code": "000001", "stock_name": "平安银行", "exchange_type": "SZSE"},
                {"stock_code": "830001", "stock_name": "示例北交所", "exchange_type": "BSE"},
                {"stock_code": "920000", "stock_name": "示例新代码", "exchange_type": "BSE"},
            ],
        }
    }
    monkeypatch.setattr(
        "paper.watch.universe.requests.get",
        lambda *args, **kwargs: _Response(body),
    )

    candidates, errors = load_full_market_universe("http://java", "2026-08-19")

    assert [(item.stock_code, item.exchange_type) for item in candidates] == [
        ("600519", "SH"),
        ("000001", "SZ"),
        ("830001", "BJ"),
        ("920000", "BJ"),
    ]
    assert errors == []


def test_bse_limit_price_fallback_uses_thirty_percent():
    quote = Quote(
        code="920000",
        name="示例新代码",
        exchange="BJ",
        last=130.0,
        pct_chg=0.0,
        volume=1000,
        market_time=datetime(2026, 8, 19, 10, 0),
        received_at=datetime(2026, 8, 19, 10, 0),
        validity=QuoteValidity.VALID,
    )

    derive_limit_up_price(quote)

    assert quote.limit_up_price == 169.0
