from __future__ import annotations

from datetime import datetime, timedelta

from paper.config import PaperConfig
from paper.models import Candidate, QuoteValidity
from paper.quote_client import EASTMONEY_ULIST_ENDPOINT, QuoteClient


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

    def fake_get(url, *, params, headers, timeout, proxies=None):
        calls.append({"url": url, **params})
        return _Response(_payload([_row("600519", pct=-3.25)]))

    class _Now(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW

    monkeypatch.setattr("paper.quote_client.requests.get", fake_get)
    monkeypatch.setattr("paper.quote_client.datetime", _Now)
    candidate = Candidate("600519", "贵州茅台", "SH")
    quote, result = _client().snapshot_many([candidate], received_at=NOW)["600519"]

    assert result.ok is True
    assert quote.pct_chg == -3.25
    assert quote.market_time == NOW
    assert quote.validity == QuoteValidity.VALID
    assert quote.freshness_seconds == 0
    assert calls[0]["url"] == EASTMONEY_ULIST_ENDPOINT
    assert "1.600519" in calls[0]["secids"]


def test_snapshot_many_batches_secids_across_requests(monkeypatch):
    requested_secids = []

    def fake_get(url, *, params, headers, timeout, proxies=None):
        requested_secids.append(params["secids"])
        codes = [part.split(".", 1)[1] for part in params["secids"].split(",")]
        return _Response(_payload([_row(code) for code in codes]))

    class _Now(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW

    monkeypatch.setattr("paper.quote_client.requests.get", fake_get)
    monkeypatch.setattr("paper.quote_client.datetime", _Now)
    candidates = [
        Candidate(f"{index:06d}", f"股票{index:06d}", "SZ" if index < 100 else "SH")
        for index in range(150)
    ]
    # 让第 2 批包含目标代码，验证不会只扫第一批。
    candidates.append(Candidate("600519", "贵州茅台", "SH"))
    out = _client().snapshot_many(candidates, received_at=NOW)

    assert len(requested_secids) == 2  # 80 + 71
    assert out["600519"][0].code == "600519"
    assert out["000001"][0].validity == QuoteValidity.VALID


def test_snapshot_many_continues_after_one_batch_http_error(monkeypatch):
    import requests as req

    calls = {"n": 0}

    def fake_get(url, *, params, headers, timeout, proxies=None):
        calls["n"] += 1
        if calls["n"] == 1:
            raise req.ConnectionError("boom")
        codes = [part.split(".", 1)[1] for part in params["secids"].split(",")]
        return _Response(_payload([_row(code) for code in codes]))

    class _Now(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW

    monkeypatch.setattr("paper.quote_client.requests.get", fake_get)
    monkeypatch.setattr("paper.quote_client.datetime", _Now)
    candidates = [
        Candidate(f"{index:06d}", f"股票{index:06d}", "SZ")
        for index in range(100)
    ]
    client = _client(max_retry=1)
    out = client.snapshot_many(candidates, received_at=NOW)

    # 第一批 80 只失败，第二批 20 只成功。
    assert out["000000"][0] is None
    assert out["000080"][0].code == "000080"
    assert client.last_batch_meta["page_count"] == 2
    assert client.last_batch_meta["transport"] == "ulist_secids"


def test_snapshot_many_marks_stale_and_missing_market_time(monkeypatch):
    rows = [
        _row("600519", timestamp=NOW - timedelta(minutes=10)),
        {**_row("000001"), "f124": "-"},
    ]

    class _Now(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW

    monkeypatch.setattr(
        "paper.quote_client.requests.get",
        lambda *args, **kwargs: _Response(_payload(rows)),
    )
    monkeypatch.setattr("paper.quote_client.datetime", _Now)
    candidates = [
        Candidate("600519", "贵州茅台", "SH"),
        Candidate("000001", "平安银行", "SZ"),
    ]
    out = _client(quote_stale_sec=120).snapshot_many(candidates, received_at=NOW)

    assert out["600519"][0].validity == QuoteValidity.STALE
    assert out["000001"][0].validity == QuoteValidity.INVALID
    assert out["000001"][0].error == "MARKET_TIME_UNAVAILABLE"


def test_snapshot_many_batch_received_at_avoids_future_false_positive(monkeypatch):
    """扫描起点早于后半批行情时间时，不得把有效行情打成 FUTURE_TIMESTAMP。"""
    scan_start = NOW
    later_market = NOW + timedelta(minutes=3)

    def fake_get(url, *, params, headers, timeout, proxies=None):
        return _Response(_payload([_row("600519", timestamp=later_market)]))

    class _FrozenDatetime(datetime):
        _calls = 0

        @classmethod
        def now(cls, tz=None):
            cls._calls += 1
            # 第一次（批到达）返回扫描后 3 分钟，模拟长耗时拉取。
            return later_market if cls._calls >= 1 else scan_start

    monkeypatch.setattr("paper.quote_client.requests.get", fake_get)
    monkeypatch.setattr("paper.quote_client.datetime", _FrozenDatetime)
    quote, _ = _client().snapshot_many(
        [Candidate("600519", "贵州茅台", "SH")],
        received_at=scan_start,
    )["600519"]

    assert quote.validity == QuoteValidity.VALID
    assert quote.error != "FUTURE_TIMESTAMP"
    assert quote.received_at == later_market
