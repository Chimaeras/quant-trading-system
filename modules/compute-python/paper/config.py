"""Paper Live 配置：环境变量、限额与常量（妙想模拟盘专用，密钥只走环境变量）。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field

# 域名白名单：只允许官方妙想网关
ALLOWED_API_BASE = "https://mkapi2.dfcfs.com/finskillshub"

# 交易时段（本地时间，A 股连续竞价）
TRADING_SESSIONS = (("09:30", "11:30"), ("13:00", "15:00"))

# 费用口径（与 Java TradingFeePolicy 对齐）
COMMISSION_RATE = 0.0003
MIN_COMMISSION = 5.0
STAMP_TAX_RATE = 0.0005  # 仅卖出
SLIPPAGE_RATE = 0.0002
LOT_SIZE = 100

# 限价小数位：沪市 2 位，深市 3 位
PRICE_DECIMALS = {"SH": 2, "SZ": 3}

# 妙想 buyability 中文枚举（T1CandidateService 实际产出）
BUYABILITY_BLOCKED = "大概率买不进"
BUYABILITY_CAUTION = "高开追高谨慎"
BUYABILITY_OK = "可买"


@dataclass
class PaperConfig:
    """Paper Live 全量配置快照（进入 PaperRun 的 config_snapshot 即此对象序列化）。"""

    api_key: str = ""
    api_base: str = ALLOWED_API_BASE
    halt: bool = False
    allow_chinext: bool = False
    allow_star: bool = False
    allow_market: bool = False
    max_trades_per_run: int = 5
    max_name_weight: float = 0.20
    quote_stale_sec: int = 120
    slippage_bps: int = 20
    candidate_file: str = ""
    t1_base: str = "http://localhost:8080"
    java_token: str = ""
    max_retry: int = 3
    query_interval_sec: int = 60
    max_observe_codes: int = 10
    extra: dict = field(default_factory=dict)

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "PaperConfig":
        """从环境变量构建配置；域名不符直接抛错（fail-closed）。"""
        e = env if env is not None else dict(os.environ)
        api_base = e.get("MX_API_URL", ALLOWED_API_BASE).rstrip("/")
        if api_base != ALLOWED_API_BASE:
            raise ValueError(f"MX_API_URL 只允许官方基址 {ALLOWED_API_BASE}，当前: {api_base}")
        api_key = e.get("MX_APIKEY", "")
        if not api_key:
            raise ValueError("未配置 MX_APIKEY 环境变量（妙想 API 密钥必填，禁止写入文件或代码）")
        return cls(
            api_key=api_key,
            api_base=api_base,
            halt=e.get("PAPER_HALT", "0") == "1",
            allow_chinext=e.get("PAPER_ALLOW_CHINEXT", "0") == "1",
            allow_star=e.get("PAPER_ALLOW_STAR", "0") == "1",
            allow_market=e.get("PAPER_ALLOW_MARKET", "0") == "1",
            max_trades_per_run=int(e.get("PAPER_MAX_TRADES_PER_RUN", "5")),
            max_name_weight=float(e.get("PAPER_MAX_NAME_WEIGHT", "0.20")),
            quote_stale_sec=int(e.get("PAPER_QUOTE_STALE_SEC", "120")),
            slippage_bps=int(e.get("PAPER_SLIPPAGE_BPS", "20")),
            candidate_file=e.get("PAPER_CANDIDATE_FILE", ""),
            t1_base=e.get("PAPER_T1_BASE", "http://localhost:8080"),
            java_token=e.get("JAVA_TOKEN", ""),
            max_retry=int(e.get("PAPER_MAX_RETRY", "3")),
            query_interval_sec=int(e.get("PAPER_QUERY_INTERVAL_SEC", "60")),
            max_observe_codes=int(e.get("PAPER_MAX_OBSERVE_CODES", "10")),
        )

    def public_snapshot(self) -> dict:
        """对外快照：不含密钥（contains_secret=false 的保证）。"""
        return {
            "api_base": self.api_base,
            "halt": self.halt,
            "allow_chinext": self.allow_chinext,
            "allow_star": self.allow_star,
            "allow_market": self.allow_market,
            "max_trades_per_run": self.max_trades_per_run,
            "max_name_weight": self.max_name_weight,
            "quote_stale_sec": self.quote_stale_sec,
            "slippage_bps": self.slippage_bps,
            "max_retry": self.max_retry,
            "query_interval_sec": self.query_interval_sec,
            "max_observe_codes": self.max_observe_codes,
        }
