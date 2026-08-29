"""配置与 broker payload 构造测试（全离线）。"""
from __future__ import annotations

import pytest

from paper.config import ALLOWED_API_BASE, PaperConfig


class TestConfig:
    """环境变量加载与 fail-closed。"""

    def _env(self, **overrides) -> dict:
        env = {"MX_APIKEY": "test-key"}
        env.update(overrides)
        return env

    def test_defaults(self):
        config = PaperConfig.from_env(self._env())
        assert config.api_base == ALLOWED_API_BASE
        assert config.max_trades_per_run == 5
        assert config.max_name_weight == pytest.approx(0.20)
        assert config.allow_chinext is False
        assert config.allow_star is False
        assert config.allow_market is False

    def test_missing_key_only_blocks_external_or_mx_query(self):
        internal = PaperConfig.from_env({
            "PAPER_WATCH_EXECUTION_MODE": "INTERNAL_PAPER",
            "PAPER_KLINE_SOURCE": "eastmoney",
        })
        assert internal.api_key == ""
        with pytest.raises(ValueError, match="MX_APIKEY"):
            PaperConfig.from_env({"PAPER_WATCH_EXECUTION_MODE": "EXTERNAL_PAPER"})
        with pytest.raises(ValueError, match="MX_APIKEY"):
            PaperConfig.from_env({"PAPER_KLINE_SOURCE": "mx_query"})

    def test_wrong_base_raises(self):
        with pytest.raises(ValueError, match="只允许官方基址"):
            PaperConfig.from_env(self._env(
                MX_API_URL="https://evil.example.com",
                PAPER_WATCH_EXECUTION_MODE="EXTERNAL_PAPER",
            ))

    def test_public_snapshot_no_secret(self):
        config = PaperConfig.from_env(self._env())
        snapshot = config.public_snapshot()
        assert "api_key" not in snapshot
        assert "test-key" not in repr(snapshot)


class TestBoardBlocked:
    """板块闸代码段判断。"""

    def test_chinext_blocked(self):
        from paper.models import Candidate

        cand = Candidate(stock_code="300750", stock_name="宁德时代", exchange_type="SZ")
        assert cand.board_blocked(False, False) == "BOARD_BLOCKED_CHINEXT"
        assert cand.board_blocked(True, False) is None

    def test_star_blocked(self):
        from paper.models import Candidate

        cand = Candidate(stock_code="688981", stock_name="中芯国际", exchange_type="SH")
        assert cand.board_blocked(False, False) == "BOARD_BLOCKED_STAR"
        assert cand.board_blocked(False, True) is None

    def test_mainboard_ok(self):
        from paper.models import Candidate

        cand = Candidate(stock_code="600519", stock_name="贵州茅台", exchange_type="SH")
        assert cand.board_blocked(False, False) is None
