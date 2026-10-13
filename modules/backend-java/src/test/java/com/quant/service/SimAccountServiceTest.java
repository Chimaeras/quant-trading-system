package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.PositionInfo;
import com.quant.entity.PositionLot;
import com.quant.entity.UserAccount;
import com.quant.exception.BusinessException;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.PositionInfoRepository;
import com.quant.repository.PositionLotRepository;
import com.quant.repository.SimulationSessionRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.TradingLogRepository;
import com.quant.repository.UserAccountRepository;
import com.quant.security.UserIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SimAccountService 回归测试。
 *
 * <p>V2-MP-021 重构后服务依赖 11 个 bean（含 lot/session 维度的 T+1 与成本口径），
 * 可卖量改为基于 PositionLot 批次计算（建仓日严格早于目标交易日的未卖量之和），
 * 不再依赖 trading_log 的 sumBuyVolumeBetween，也不再按 accountId+stockCode 直接查持仓。
 */
class SimAccountServiceTest {

    private static final long LIVE_SESSION = 0L;

    private AuthService authService;
    private UserAccountRepository userAccountRepository;
    private PositionInfoRepository positionInfoRepository;
    private TradingLogRepository tradingLogRepository;
    private MarketDataRepository marketDataRepository;
    private MarketPriceRawRepository marketPriceRawRepository;
    private StockInfoRepository stockInfoRepository;
    private TradingCalendarService tradingCalendarService;
    private SimulationSessionRepository simulationSessionRepository;
    private PositionLotRepository positionLotRepository;
    private UserIdResolver userIdResolver;
    private SimAccountService service;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        userAccountRepository = mock(UserAccountRepository.class);
        positionInfoRepository = mock(PositionInfoRepository.class);
        tradingLogRepository = mock(TradingLogRepository.class);
        marketDataRepository = mock(MarketDataRepository.class);
        marketPriceRawRepository = mock(MarketPriceRawRepository.class);
        stockInfoRepository = mock(StockInfoRepository.class);
        tradingCalendarService = mock(TradingCalendarService.class);
        simulationSessionRepository = mock(SimulationSessionRepository.class);
        positionLotRepository = mock(PositionLotRepository.class);
        userIdResolver = mock(UserIdResolver.class);
        // 鉴权关闭的本地单用户模式：显式 userId 原样返回
        when(userIdResolver.resolve(anyLong())).thenAnswer(inv -> inv.getArgument(0));
        // R6-01：日历覆盖判定与 isTradingDay 是两个独立判据（覆盖区间之外 false
        // 不可断言为非交易日）。默认给一个足够宽的覆盖区间，使本类既有用例继续
        // 只用 isTradingDay 控制交易日语义；覆盖缺口的行为由专门用例验证。
        org.mockito.Mockito.lenient().when(tradingCalendarService.coverageWindow())
                .thenReturn(new LocalDate[]{LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31)});
        service = new SimAccountService(
                authService,
                userAccountRepository,
                positionInfoRepository,
                tradingLogRepository,
                marketDataRepository,
                marketPriceRawRepository,
                stockInfoRepository,
                tradingCalendarService,
                simulationSessionRepository,
                positionLotRepository,
                userIdResolver
        );
    }

    @Test
    void blocksSameDaySellUnderTPlusOne() {
        UserAccount account = accountWithCash(100_000);
        PositionInfo position = new PositionInfo();
        position.setId(1L);
        position.setAccountId(10L);
        position.setStockCode("000001");
        position.setExchangeType("SZSE");
        position.setVolume(100);
        position.setAvailableVolume(0);
        position.setAvgCost(BigDecimal.TEN);

        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(account));
        when(positionInfoRepository.findByAccountIdAndSessionIdAndStockCodeAndExchangeType(
                10L, LIVE_SESSION, "000001", "SZSE")).thenReturn(Optional.of(position));
        // T+1：唯一批次建仓日 = 当日（operationDate=now），不早于 as-of → 可卖量为 0
        PositionLot todayLot = new PositionLot();
        todayLot.setRemainingVolume(100);
        todayLot.setAcquisitionTradeDate(LocalDate.now());
        when(positionLotRepository.findByAccountIdAndSessionIdAndStockCodeAndExchangeType(
                10L, LIVE_SESSION, "000001", "SZSE")).thenReturn(List.of(todayLot));

        Map<String, Object> request = Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "sell",
                "volume", 100,
                "price", 10
        );

        Map<String, Object> result = service.placeOrder(1L, request);
        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("T1_FROZEN");
    }

    // 注：原 refreshSellableVolumeExcludesTodayBuys / refreshesSellableVolumeAfterBuyLogIsPersisted
    // 直接调用 refreshSellableVolume(PositionInfo, Long)（2 参数 public）。
    // V2-MP-021 后该签名变为 private refreshSellableVolume(PositionInfo, Long accountId,
    // Long sessionId, LocalDate asOf)，且可卖量改由 PositionLot 批次聚合计算，
    // 旧的单参数入口已不存在，这两个测试不再适用，故移除。

    @Test
    void blocksBuyAtLimitUpWhenPreCloseDerivedFromPreviousClose() {
        UserAccount account = accountWithCash(100_000);
        LocalDate today = LocalDate.of(2026, 6, 30);
        MarketData bar = new MarketData();
        bar.setTradeDate(today);
        bar.setClosePrice(BigDecimal.valueOf(11));
        bar.setVolume(1_000_000L);
        MarketData prevBar = new MarketData();
        prevBar.setTradeDate(today.minusDays(1));
        prevBar.setClosePrice(BigDecimal.valueOf(10));

        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(account));
        // 在线模式：传 price 时 operationDate=now()，ensureTradableAtPrice 查
        // findByStockCodeAndExchangeTypeAndTradeDate(code, ex, now())。用 any 匹配动态 today。
        when(marketDataRepository.findByStockCodeAndExchangeTypeAndTradeDate(
                eq("000001"), eq("SZSE"), any(LocalDate.class))).thenReturn(Optional.of(bar));
        when(marketDataRepository.findFirstByStockCodeAndExchangeTypeAndTradeDateLessThanOrderByTradeDateDesc(
                eq("000001"), eq("SZSE"), any(LocalDate.class))).thenReturn(Optional.of(prevBar));
        when(stockInfoRepository.findByStockCodeAndExchangeType("000001", "SZSE")).thenReturn(Optional.empty());

        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "buy",
                "volume", 100,
                "price", 11
        ));
        // 委托价 11 = 前收 10 × (1 + 10%) → 涨停封板，买单被拒
        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("NOT_TRADABLE");
        assertThat(String.valueOf(result.get("reject_reason"))).contains("涨停");
    }

    @Test
    void blocksBuyAtLimitUpPrice() {
        UserAccount account = accountWithCash(100_000);
        MarketData bar = new MarketData();
        bar.setTradeDate(LocalDate.now());
        bar.setPreClose(BigDecimal.valueOf(10));
        bar.setClosePrice(BigDecimal.valueOf(11));
        bar.setVolume(1_000_000L);

        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(account));
        when(marketDataRepository.findByStockCodeAndExchangeTypeAndTradeDate(
                eq("000001"), eq("SZSE"), any(LocalDate.class))).thenReturn(Optional.of(bar));
        when(stockInfoRepository.findByStockCodeAndExchangeType("000001", "SZSE")).thenReturn(Optional.empty());

        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "buy",
                "volume", 100,
                "price", 11
        ));
        // 委托价 11 = 前收 10 × (1 + 10%) → 涨停封板，买单被拒
        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("NOT_TRADABLE");
        assertThat(String.valueOf(result.get("reject_reason"))).contains("涨停");
    }

    @Test
    void blocksOrderWhenLatestBarHasZeroVolume() {
        UserAccount account = accountWithCash(100_000);
        MarketData bar = new MarketData();
        bar.setTradeDate(LocalDate.now());
        bar.setClosePrice(BigDecimal.TEN);
        bar.setVolume(0L);

        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(account));
        when(marketDataRepository.findByStockCodeAndExchangeTypeAndTradeDate(
                eq("000001"), eq("SZSE"), any(LocalDate.class))).thenReturn(Optional.of(bar));
        when(stockInfoRepository.findByStockCodeAndExchangeType("000001", "SZSE")).thenReturn(Optional.empty());

        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "buy",
                "volume", 100,
                "price", 10
        ));
        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(String.valueOf(result.get("reject_reason"))).contains("停牌");
    }

    private static UserAccount accountWithCash(double cash) {
        UserAccount account = new UserAccount();
        account.setId(10L);
        account.setUserId(1L);
        account.setStatus(1);
        account.setAvailableCash(BigDecimal.valueOf(cash));
        return account;
    }

    // ---------- 客户端入参解析：非法日期/金额必须报业务错，不得冒泡成 500 ----------

    @Test
    void createSessionRejectsMalformedStartDate() {
        when(userAccountRepository.findFirstByUserIdAndStatus(1L, 1)).thenReturn(Optional.of(accountWithCash(100_000)));

        // "2026-6-30" 不是 ISO yyyy-MM-dd，旧实现 LocalDate.parse 直接抛 DateTimeParseException → 500
        assertThatThrownBy(() -> service.createSession(1L, Map.of("startDate", "2026-6-30")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("日期格式非法");
    }

    @Test
    void createSessionRejectsNonNumericCash() {
        when(userAccountRepository.findFirstByUserIdAndStatus(1L, 1)).thenReturn(Optional.of(accountWithCash(100_000)));
        when(tradingCalendarService.isTradingDay(any(LocalDate.class))).thenReturn(true);

        assertThatThrownBy(() -> service.createSession(1L, Map.of("startDate", "2026-06-30", "cash", "十万")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cash")
                .hasMessageContaining("不是合法数字");
    }

    @Test
    void createSessionRejectsNonPositiveCash() {
        when(tradingCalendarService.isTradingDay(any(LocalDate.class))).thenReturn(true);

        // 会话起始资金是全部收益率的基准：0 会让 returnRate 除零，负数直接给出虚假正收益
        when(userAccountRepository.findFirstByUserIdAndStatus(1L, 1)).thenReturn(Optional.of(accountWithCash(100_000)));
        assertThatThrownBy(() -> service.createSession(1L, Map.of("startDate", "2026-06-30", "cash", 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须为正数");
        assertThatThrownBy(() -> service.createSession(1L, Map.of("startDate", "2026-06-30", "cash", -1000)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须为正数");

        // 未传 cash 时取账户可用资金，同样不能是 0
        when(userAccountRepository.findFirstByUserIdAndStatus(1L, 1)).thenReturn(Optional.of(accountWithCash(0)));
        assertThatThrownBy(() -> service.createSession(1L, Map.of("startDate", "2026-06-30")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须为正数");
    }

    @Test
    void placeOrderRejectsNonNumericPrice() {
        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(accountWithCash(100_000)));

        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "buy",
                "volume", 100,
                "price", "十元"
        ));
        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("PRICE_RESOLVE_FAILED");
        assertThat(String.valueOf(result.get("reject_reason"))).contains("不是合法数字");
    }

    // ---------- R33-P1-3：非正成交价必须拒绝（负价会让买单资金校验恒不成立） ----------

    @Test
    void placeOrderRejectsNegativeClientPrice() {
        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(accountWithCash(100_000)));

        // 修复前：price=-10 → cost=-1000，availableCash.compareTo(cost)<0 恒为 false，
        // 资金校验被绕过；TradingFeePolicy.breakdown 对 gross<=0 又直接返零费用，无第二道闸门。
        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "buy",
                "volume", 100,
                "price", -10
        ));
        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("PRICE_RESOLVE_FAILED");
        assertThat(String.valueOf(result.get("reject_reason"))).contains("必须为正数");
    }

    @Test
    void placeOrderRejectsZeroClientPrice() {
        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(accountWithCash(100_000)));

        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "buy",
                "volume", 100,
                "price", 0
        ));
        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("PRICE_RESOLVE_FAILED");
        assertThat(String.valueOf(result.get("reject_reason"))).contains("必须为正数");
    }

    @Test
    void placeOrderRejectsNonPositiveRawClosePrice() {
        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1)).thenReturn(List.of(accountWithCash(100_000)));
        MarketPriceRaw raw = new MarketPriceRaw();
        raw.setStockCode("000001");
        raw.setExchangeType("SZSE");
        raw.setTradeDate(LocalDate.now());
        // 库内脏收盘价：同一道闸门必须拦住，不能因为「来自 database」就放行
        raw.setClosePrice(BigDecimal.valueOf(-1));
        when(marketPriceRawRepository.findByStockCodeAndExchangeTypeAndTradeDate(
                eq("000001"), eq("SZSE"), any(LocalDate.class))).thenReturn(Optional.of(raw));

        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "buy",
                "volume", 100
        ));
        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("PRICE_RESOLVE_FAILED");
        assertThat(String.valueOf(result.get("reject_reason"))).contains("必须为正数");
    }

    // ---------- R31-P1-2：下单须持账户行悲观锁 ----------

    @Test
    void placeOrderResolvesAccountWithPessimisticRowLock() {
        when(userAccountRepository.findAllForUpdateByUserIdAndStatus(1L, 1))
                .thenReturn(List.of(accountWithCash(100_000)));

        // 空代码在「账户已解析」之后被拒——足以证明取账户走的是带锁查询
        Map<String, Object> result = service.placeOrder(1L, Map.of(
                "symbol", "",
                "action", "buy",
                "volume", 100
        ));

        assertThat(String.valueOf(result.get("reject_code"))).isEqualTo("INVALID_SYMBOL");
        verify(userAccountRepository).findAllForUpdateByUserIdAndStatus(1L, 1);
        // 下单不得再走无锁查询：读-改-写 availableCash 无行锁会丢更新（并发委托各扣一次）
        verify(userAccountRepository, never()).findFirstByUserIdAndStatus(anyLong(), any());
    }
}
