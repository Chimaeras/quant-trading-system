package com.quant.service;

import com.quant.config.AuthProperties;
import com.quant.entity.PositionInfo;
import com.quant.entity.UserAccount;
import com.quant.exception.BusinessException;
import com.quant.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimAccountServiceTest {

    private AuthProperties authProperties;
    private AuthService authService;
    private UserAccountRepository userAccountRepository;
    private PositionInfoRepository positionInfoRepository;
    private TradingLogRepository tradingLogRepository;
    private MarketDataRepository marketDataRepository;
    private StockInfoRepository stockInfoRepository;
    private SimAccountService service;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authProperties.setEnabled(false);
        authService = mock(AuthService.class);
        userAccountRepository = mock(UserAccountRepository.class);
        positionInfoRepository = mock(PositionInfoRepository.class);
        tradingLogRepository = mock(TradingLogRepository.class);
        marketDataRepository = mock(MarketDataRepository.class);
        stockInfoRepository = mock(StockInfoRepository.class);
        service = new SimAccountService(
                authProperties,
                authService,
                userAccountRepository,
                positionInfoRepository,
                tradingLogRepository,
                marketDataRepository,
                stockInfoRepository
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

        when(userAccountRepository.findFirstByUserIdAndStatus(1L, 1)).thenReturn(Optional.of(account));
        when(positionInfoRepository.findByAccountIdAndStockCodeAndExchangeType(10L, "000001", "SZSE"))
                .thenReturn(Optional.of(position));
        when(tradingLogRepository.sumBuyVolumeBetween(eq(10L), eq("000001"), eq("SZSE"), any(), any()))
                .thenReturn(100);

        Map<String, Object> request = Map.of(
                "stockCode", "000001",
                "exchangeType", "SZSE",
                "action", "sell",
                "volume", 100,
                "price", 10
        );

        assertThatThrownBy(() -> service.placeOrder(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("T+1");
    }

    @Test
    void refreshSellableVolumeExcludesTodayBuys() {
        PositionInfo position = new PositionInfo();
        position.setId(1L);
        position.setStockCode("000001");
        position.setExchangeType("SZSE");
        position.setVolume(300);
        when(tradingLogRepository.sumBuyVolumeBetween(eq(10L), eq("000001"), eq("SZSE"), any(), any()))
                .thenReturn(100);

        service.refreshSellableVolume(position, 10L);

        assertThat(position.getAvailableVolume()).isEqualTo(200);
        verify(positionInfoRepository).save(position);
    }

    private static UserAccount accountWithCash(double cash) {
        UserAccount account = new UserAccount();
        account.setId(10L);
        account.setUserId(1L);
        account.setStatus(1);
        account.setAvailableCash(BigDecimal.valueOf(cash));
        return account;
    }
}
