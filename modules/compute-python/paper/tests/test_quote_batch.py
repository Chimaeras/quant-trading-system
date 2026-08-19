from __future__ import annotations

from datetime import datetime, timedelta

from paper.config import PaperConfig
from paper.models import Candidate, QuoteValidity
from paper.quote_client import QuoteClient


NOW = datetime(2026, 8, 19, 10, 0, 0)


class _Http:
    def save_raw(self, run_id, endpoint, payload):
        return f"{run_id}/{endpoint}.json"


class _Response:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


def _row(code: str, *, pct=-2.5, timestamp: datetime = NOW):
    return {
        "f2": 10.25,
        "f3": pct,
        "f5": 1234,
        "f6": 5000000,
        "f12": code,
        "f13": 1 if code.startswith("6") else 0,
        "f14": f"股票{code}",
        "f124": int(timestamp.timestamp()),
    }


def _payload(rows, total=None):
    return {"rc": 0, "data": {"total": total if total is not None else len(rows), "diff": rows}}


def _client(**config):
    return QuoteClient(_Http(), PaperConfig(api_key="test", **config))


def test_snapshot_many_keeps_negative_pct_and_provider_time(monkeypatch):
    calls = []

    def fake_get(url, *, params, headers, timeout):
        calls.append(params)
        return _Response(_payload([_row("600519", pct=-3.25)]))

    monkeypatch.setattr("paper.quote_client.requests.get", fake_get)
    candidate = Candidate("600519", "贵州茅台", "SH")
    quote, result = _client().snapshot_many([candidate], received_at=NOW)["600519"]

    assert result.ok is True
    assert quote.pct_chg == -3.25
    assert quote.market_time == NOW
    assert quote.validity == QuoteValidity.VALID
    assert quote.freshness_seconds == 0
    assert calls[0]["fid"] == "f12"
    assert "secids" not in calls[0]


def test_snapshot_many_pages_market_until_requested_code_is_found(monkeypatch):
    first_page = [_row(f"{index:06d}") for index in range(1000)]
    second_page = [_row("600519")]
    requested_pages = []

    def fake_get(url, *, params, headers, timeout):
        page = int(params["pn"])
        requested_pages.append(page)
        return _Response(_payload(first_page if page == 1 else second_page, total=1001))

    monkeypatch.setattr("paper.quote_client.requests.get", fake_get)
    candidate = Candidate("600519", "贵州茅台", "SH")
    quote, result = _client().snapshot_many([candidate], received_at=NOW)["600519"]

    assert requested_pages == [1, 2]
    assert result.ok is True
    assert quote.code == "600519"


def test_snapshot_many_marks_stale_and_missing_market_time(monkeypatch):
    rows = [
        _row("600519", timestamp=NOW - timedelta(minutes=10)),
        {**_row("000001"), "f124": "-"},
    ]
    monkeypatch.setattr(
        "paper.quote_client.requests.get",
        lambda *args, **kwargs: _Response(_payload(rows)),
    )
    candidates = [
        Candidate("600519", "贵州茅台", "SH"),
        Candidate("000001", "平安银行", "SZ"),
    ]
    out = _client(quote_stale_sec=120).snapshot_many(candidates, received_at=NOW)

    assert out["600519"][0].validity == QuoteValidity.STALE
    assert out["000001"][0].validity == QuoteValidity.INVALID
    assert out["000001"][0].error == "MARKET_TIME_UNAVAILABLE"


def test_snapshot_many_repeated_page_fails_unresolved_codes(monkeypatch):
    repeated = [_row(f"{index:06d}") for index in range(1000)]
    monkeypatch.setattr(
        "paper.quote_client.requests.get",
        lambda *args, **kwargs: _Response(_payload(repeated, total=3000)),
    )
    candidate = Candidate("600519", "贵州茅台", "SH")
    quote, result = _client().snapshot_many([candidate], received_at=NOW)["600519"]

    assert quote is None
    assert result.ok is False
    assert result.code == "PAGINATION_REPEATED"
