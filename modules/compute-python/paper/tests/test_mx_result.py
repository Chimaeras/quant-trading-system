"""MxResult 包络归一化与错误码分类测试（全离线，fixture 来自真实抓包）。"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from paper.mx_result import MxErrorKind, MxResult
from paper.mx_http import raw_hash, sanitize_payload

FIXTURES = Path(__file__).parent / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class TestEnvelope:
    """双成功包络：交易类 code="200"（字符串），行情类 success/status=0。"""

    def test_trade_envelope_string_code(self):
        result = MxResult.from_json("/mockTrading/balance", _load("balance_success.json"))
        assert result.ok is True
        assert result.kind == MxErrorKind.OK
        assert result.data["totalAssets"] == 200000.0

    def test_query_envelope_success_true(self):
        result = MxResult.from_json("/api/claw/query", _load("query_success.json"))
        assert result.ok is True

    def test_unbound_404(self):
        result = MxResult.from_json("/mockTrading/balance", _load("error_404_unbound.json"))
        assert result.ok is False
        assert result.kind == MxErrorKind.NOT_BOUND
        assert result.stop_run() is True

    def test_key_invalid_114(self):
        result = MxResult.from_json("/mockTrading/balance", _load("error_114_key.json"))
        assert result.kind == MxErrorKind.KEY_INVALID
        assert result.stop_run() is True
        assert result.retryable() is False

    def test_quota_113(self):
        result = MxResult.from_json("/api/claw/query", _load("error_113_quota.json"))
        assert result.kind == MxErrorKind.QUOTA_EXHAUSTED
        assert result.stop_run() is True


class TestSanitize:
    """脱敏与哈希。"""

    def test_sanitize_removes_uid(self):
        payload = {"code": "200", "data": {"uid": "123", "accUID": "123", "totalAssets": 1.0}}
        cleaned = sanitize_payload(payload)
        assert "uid" not in cleaned["data"] and "accUID" not in cleaned["data"]
        assert cleaned["data"]["totalAssets"] == 1.0

    def test_raw_hash_stable(self):
        payload = {"b": 1, "a": 2}
        assert raw_hash(payload) == raw_hash({"a": 2, "b": 1})
        assert len(raw_hash(payload)) == 16


class TestBalanceModel:
    """Balance fail-closed 解析。"""

    def test_from_real_capture(self):
        from paper.models import Balance

        data = _load("balance_success.json")["data"]
        balance = Balance.from_mx(data)
        assert balance.total_assets == 200000.0
        assert balance.avail_balance == 200000.0
        assert balance.acc_name == "模拟组合2958188"

    def test_missing_field_raises(self):
        from paper.models import Balance

        with pytest.raises(ValueError, match="缺少字段"):
            Balance.from_mx({"totalAssets": 1.0})
