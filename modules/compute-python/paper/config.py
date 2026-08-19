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

# 当前 universe 仅为 A 股股票，最小报价单位统一按 0.01；基金/债券接入后
# 必须由证券规则主数据覆盖，不能继续按交易所粗分。
PRICE_DECIMALS = {"SH": 2, "SZ": 2, "BJ": 2}

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
    clock_skew_sec: int = 30  # 允许的行情时钟偏差（P1-02/P2-02）
    # 盘中自动盯盘配置（watch V1）
    watch_strategy: str = "MA_CROSS"
    bar_period_min: int = 5
    # 分钟 K 线来源：eastmoney 为官方公开历史 K 线接口；mx_query 保留兼容旧问句。
    kline_source: str = "eastmoney"
    watchlist_file: str = ""
    # FULL：默认扫描 Java 股票主数据中的全部沪深 A 股；T1 保留为兼容模式。
    watch_universe_mode: str = "FULL"
    max_watch_universe_codes: int = 10000
    # live 最低行情覆盖率；低于阈值时市场中位数和持仓监控均不再可信。
    min_quote_coverage: float = 0.95
    # 批次 C 两级行情管线：L1 全市场分钟扫描，L2 活跃池秒级轮询。
    market_pipeline_v2: bool = True
    l1_scan_interval_sec: int = 60
    l2_poll_interval_sec: int = 5
    max_l2_codes: int = 30
    l2_min_residence_sec: int = 300
    quote_missing_disable_cycles: int = 2
    execution_quote_stale_sec: int = 10
    emit_quote_events: bool = False
    # 可恢复运行库与 live 单实例租约。
    watch_store_path: str = ""
    lease_ttl_sec: int = 180
    min_strategy_ready_coverage: float = 0.95
    min_store_free_mb: int = 256
    # SHADOW 只产生日志；INTERNAL_PAPER 使用本地撮合；EXTERNAL_PAPER 调妙想模拟盘。
    watch_execution_mode: str = "SHADOW"
    paper_session_id: str = "default"
    internal_initial_cash: float = 1_000_000.0
    internal_fill_participation: float = 0.10
    internal_matching_model: str = "NEXT_BAR"
    buy_order_ttl_sec: int = 600
    sell_order_ttl_sec: int = 300
    auto_cancel_expired: bool = True
    cooldown_min: int = 15
    watch_live: bool = False
    max_trades_per_day: int = 20
    max_trades_per_cycle: int = 3
    ma_short: int = 5
    ma_long: int = 20
    boll_period: int = 20
    boll_std: float = 2.0
    max_total_weight: float = 0.80
    max_positions: int = 5
    min_cash_reserve: float = 5000.0
    index_circuit_breaker: bool = True
    trading_calendar_file: str = ""
    extra: dict = field(default_factory=dict)

    def validate(self) -> None:
        """P1-11：数值范围与矛盾配置校验；错误必须在启动阶段暴露，不得拖到下单阶段。"""
        if self.max_trades_per_run <= 0:
            raise ValueError(f"max_trades_per_run 必须大于 0，当前 {self.max_trades_per_run}")
        if not (0 < self.max_name_weight <= 1):
            raise ValueError(f"max_name_weight 必须在 (0,1]，当前 {self.max_name_weight}")
        if self.quote_stale_sec <= 0:
            raise ValueError(f"quote_stale_sec 必须大于 0，当前 {self.quote_stale_sec}")
        if self.slippage_bps < 0:
            raise ValueError(f"slippage_bps 不能为负，当前 {self.slippage_bps}")
        if self.max_retry < 0:
            raise ValueError(f"max_retry 不能为负，当前 {self.max_retry}")
        if self.query_interval_sec <= 0:
            raise ValueError(f"query_interval_sec 必须大于 0，当前 {self.query_interval_sec}")
        if not (1 <= self.max_observe_codes <= 50):
            raise ValueError(f"max_observe_codes 必须在 [1,50]，当前 {self.max_observe_codes}")
        if self.clock_skew_sec < 0:
            raise ValueError(f"clock_skew_sec 不能为负，当前 {self.clock_skew_sec}")
        if self.watch_strategy not in {"MA_CROSS", "MACD", "BOLL"}:
            raise ValueError(f"watch_strategy 非法: {self.watch_strategy}")
        if self.bar_period_min <= 0 or 60 % self.bar_period_min != 0:
            raise ValueError("bar_period_min 必须是正数且能整除 60")
        if self.kline_source not in {"eastmoney", "mx_query", "auto"}:
            raise ValueError("kline_source 必须是 eastmoney/mx_query/auto")
        if self.watch_universe_mode not in {"FULL", "T1"}:
            raise ValueError("watch_universe_mode 必须是 FULL/T1")
        if not (1 <= self.max_watch_universe_codes <= 10000):
            raise ValueError("max_watch_universe_codes 必须在 [1,10000]")
        if not (0 < self.min_quote_coverage <= 1):
            raise ValueError("min_quote_coverage 必须在 (0,1] 内")
        if self.l1_scan_interval_sec <= 0 or self.l2_poll_interval_sec <= 0:
            raise ValueError("L1/L2 行情间隔必须大于 0")
        if self.l2_poll_interval_sec > self.l1_scan_interval_sec:
            raise ValueError("l2_poll_interval_sec 不能大于 l1_scan_interval_sec")
        if not (1 <= self.max_l2_codes <= 500):
            raise ValueError("max_l2_codes 必须在 [1,500]")
        if self.l2_min_residence_sec < 0:
            raise ValueError("l2_min_residence_sec 不能为负")
        if self.quote_missing_disable_cycles <= 0:
            raise ValueError("quote_missing_disable_cycles 必须大于 0")
        if self.execution_quote_stale_sec <= 0:
            raise ValueError("execution_quote_stale_sec 必须大于 0")
        if self.lease_ttl_sec <= self.query_interval_sec:
            raise ValueError("lease_ttl_sec 必须大于 query_interval_sec")
        if not (0 < self.min_strategy_ready_coverage <= 1):
            raise ValueError("min_strategy_ready_coverage 必须在 (0,1] 内")
        if self.min_store_free_mb < 0:
            raise ValueError("min_store_free_mb 不能为负")
        if self.watch_execution_mode not in {"SHADOW", "INTERNAL_PAPER", "EXTERNAL_PAPER"}:
            raise ValueError("watch_execution_mode 必须是 SHADOW/INTERNAL_PAPER/EXTERNAL_PAPER")
        if not self.paper_session_id.strip():
            raise ValueError("paper_session_id 不能为空")
        if self.internal_initial_cash <= 0:
            raise ValueError("internal_initial_cash 必须大于 0")
        if not (0 < self.internal_fill_participation <= 1):
            raise ValueError("internal_fill_participation 必须在 (0,1] 内")
        if self.internal_matching_model != "NEXT_BAR":
            raise ValueError("当前 internal_matching_model 仅支持 NEXT_BAR")
        if self.buy_order_ttl_sec <= 0 or self.sell_order_ttl_sec <= 0:
            raise ValueError("买卖订单 TTL 必须大于 0")
        if self.cooldown_min < 0:
            raise ValueError("cooldown_min 不能为负")
        if self.max_trades_per_day <= 0:
            raise ValueError("max_trades_per_day 必须大于 0")
        if self.max_trades_per_cycle <= 0:
            raise ValueError("max_trades_per_cycle 必须大于 0")
        if self.ma_short <= 0 or self.ma_long <= self.ma_short:
            raise ValueError("ma_short 必须大于 0 且小于 ma_long")
        if self.boll_period < 2 or self.boll_std <= 0:
            raise ValueError("boll_period/boll_std 非法")
        if not (0 < self.max_total_weight <= 1):
            raise ValueError("max_total_weight 必须在 (0,1] 内")
        if self.max_positions <= 0:
            raise ValueError("max_positions 必须大于 0")
        if self.min_cash_reserve < 0:
            raise ValueError("min_cash_reserve 不能为负")
        if not self.api_key or not self.api_key.strip():
            raise ValueError("api_key 不能为空或纯空白")
        if self.api_base != ALLOWED_API_BASE:
            raise ValueError(f"api_base 只允许官方基址 {ALLOWED_API_BASE}")

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
        config = cls(
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
            clock_skew_sec=int(e.get("PAPER_CLOCK_SKEW_SEC", "30")),
            watch_strategy=e.get("PAPER_WATCH_STRATEGY", "MA_CROSS").strip().upper(),
            bar_period_min=int(e.get("PAPER_BAR_PERIOD_MIN", "5")),
            kline_source=e.get("PAPER_KLINE_SOURCE", "eastmoney").strip().lower(),
            watchlist_file=e.get("PAPER_WATCHLIST_FILE", ""),
            watch_universe_mode=e.get("PAPER_WATCH_UNIVERSE_MODE", "FULL").strip().upper(),
            max_watch_universe_codes=int(e.get("PAPER_MAX_WATCH_UNIVERSE_CODES", "10000")),
            min_quote_coverage=float(e.get("PAPER_MIN_QUOTE_COVERAGE", "0.95")),
            market_pipeline_v2=e.get("PAPER_MARKET_PIPELINE_V2", "1") == "1",
            l1_scan_interval_sec=int(e.get("PAPER_L1_SCAN_INTERVAL_SEC", "60")),
            l2_poll_interval_sec=int(e.get("PAPER_L2_POLL_INTERVAL_SEC", "5")),
            max_l2_codes=int(e.get("PAPER_MAX_L2_CODES", "30")),
            l2_min_residence_sec=int(e.get("PAPER_L2_MIN_RESIDENCE_SEC", "300")),
            quote_missing_disable_cycles=int(e.get("PAPER_QUOTE_MISSING_DISABLE_CYCLES", "2")),
            execution_quote_stale_sec=int(e.get("PAPER_EXECUTION_QUOTE_STALE_SEC", "10")),
            emit_quote_events=e.get("PAPER_EMIT_QUOTE_EVENTS", "0") == "1",
            watch_store_path=e.get("PAPER_WATCH_STORE_PATH", ""),
            lease_ttl_sec=int(e.get("PAPER_LEASE_TTL_SEC", "180")),
            min_strategy_ready_coverage=float(e.get("PAPER_MIN_STRATEGY_READY_COVERAGE", "0.95")),
            min_store_free_mb=int(e.get("PAPER_MIN_STORE_FREE_MB", "256")),
            watch_execution_mode=e.get("PAPER_WATCH_EXECUTION_MODE", "SHADOW").strip().upper(),
            paper_session_id=e.get("PAPER_SESSION_ID", "default").strip(),
            internal_initial_cash=float(e.get("PAPER_INTERNAL_INITIAL_CASH", "1000000")),
            internal_fill_participation=float(e.get("PAPER_INTERNAL_FILL_PARTICIPATION", "0.10")),
            internal_matching_model=e.get("PAPER_INTERNAL_MATCHING_MODEL", "NEXT_BAR").strip().upper(),
            buy_order_ttl_sec=int(e.get("PAPER_BUY_ORDER_TTL_SEC", "600")),
            sell_order_ttl_sec=int(e.get("PAPER_SELL_ORDER_TTL_SEC", "300")),
            auto_cancel_expired=e.get("PAPER_AUTO_CANCEL_EXPIRED", "1") == "1",
            cooldown_min=int(e.get("PAPER_COOLDOWN_MIN", "15")),
            watch_live=e.get("PAPER_WATCH_LIVE", "0") == "1",
            max_trades_per_day=int(e.get("PAPER_MAX_TRADES_PER_DAY", "20")),
            max_trades_per_cycle=int(e.get("PAPER_MAX_TRADES_PER_CYCLE", "3")),
            ma_short=int(e.get("PAPER_MA_SHORT", "5")),
            ma_long=int(e.get("PAPER_MA_LONG", "20")),
            boll_period=int(e.get("PAPER_BOLL_PERIOD", "20")),
            boll_std=float(e.get("PAPER_BOLL_STD", "2")),
            max_total_weight=float(e.get("PAPER_MAX_TOTAL_WEIGHT", "0.8")),
            max_positions=int(e.get("PAPER_MAX_POSITIONS", "5")),
            min_cash_reserve=float(e.get("PAPER_MIN_CASH_RESERVE", "5000")),
            index_circuit_breaker=e.get("PAPER_INDEX_CIRCUIT_BREAKER", "1") == "1",
            trading_calendar_file=e.get("PAPER_TRADING_CALENDAR_FILE", ""),
        )
        config.validate()
        return config

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
            "clock_skew_sec": self.clock_skew_sec,
            "watch_strategy": self.watch_strategy,
            "bar_period_min": self.bar_period_min,
            "kline_source": self.kline_source,
            "watchlist_file": self.watchlist_file,
            "watch_universe_mode": self.watch_universe_mode,
            "max_watch_universe_codes": self.max_watch_universe_codes,
            "min_quote_coverage": self.min_quote_coverage,
            "market_pipeline_v2": self.market_pipeline_v2,
            "l1_scan_interval_sec": self.l1_scan_interval_sec,
            "l2_poll_interval_sec": self.l2_poll_interval_sec,
            "max_l2_codes": self.max_l2_codes,
            "l2_min_residence_sec": self.l2_min_residence_sec,
            "quote_missing_disable_cycles": self.quote_missing_disable_cycles,
            "execution_quote_stale_sec": self.execution_quote_stale_sec,
            "emit_quote_events": self.emit_quote_events,
            "watch_store_path": self.watch_store_path,
            "lease_ttl_sec": self.lease_ttl_sec,
            "min_strategy_ready_coverage": self.min_strategy_ready_coverage,
            "min_store_free_mb": self.min_store_free_mb,
            "watch_execution_mode": self.watch_execution_mode,
            "paper_session_id": self.paper_session_id,
            "internal_initial_cash": self.internal_initial_cash,
            "internal_fill_participation": self.internal_fill_participation,
            "internal_matching_model": self.internal_matching_model,
            "buy_order_ttl_sec": self.buy_order_ttl_sec,
            "sell_order_ttl_sec": self.sell_order_ttl_sec,
            "auto_cancel_expired": self.auto_cancel_expired,
            "cooldown_min": self.cooldown_min,
            "watch_live": self.watch_live,
            "max_trades_per_day": self.max_trades_per_day,
            "max_trades_per_cycle": self.max_trades_per_cycle,
            "ma_short": self.ma_short,
            "ma_long": self.ma_long,
            "boll_period": self.boll_period,
            "boll_std": self.boll_std,
            "max_total_weight": self.max_total_weight,
            "max_positions": self.max_positions,
            "min_cash_reserve": self.min_cash_reserve,
            "index_circuit_breaker": self.index_circuit_breaker,
            "trading_calendar_file": self.trading_calendar_file,
        }
