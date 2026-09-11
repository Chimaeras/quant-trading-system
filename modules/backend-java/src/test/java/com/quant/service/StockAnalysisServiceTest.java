package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StockAnalysisService 回归测试：长度守卫必须基于「过滤掉空收盘价之后」的有效序列。
 *
 * <p>旧实现只判断 {@code bars.size() < 70}，而循环会把 closePrice 为 null 的 bar 跳过，
 * 于是「bars 够数但有效收盘不足 60」时仍会进入评分：ma60/ma120 靠 avg 内部 clamp
 * 静默退化成「50 根均值」，并以 MA60 的名义参与趋势评分与建议输出。
 */
class StockAnalysisServiceTest {

    private MarketDataRepository marketDataRepository;
    private StockAnalysisService service;

    @BeforeEach
    void setUp() {
        marketDataRepository = mock(MarketDataRepository.class);
        service = new StockAnalysisService(marketDataRepository);
    }

    @Test
    void nullClosesAreFilteredBeforeLengthGuard() {
        List<MarketData> bars = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            // 前 30 根 closePrice 缺失：bars.size()=80 过守卫，有效收盘只有 50 根
            bars.add(bar(LocalDate.of(2026, 1, 1).plusDays(i), i < 30 ? null : BigDecimal.valueOf(10 + i * 0.1)));
        }
        stubBars(bars);

        Map<String, Object> result = service.analyze(Map.of("stockCode", "000001", "exchange", "SZSE"));

        assertThat(result.get("data_insufficient")).isEqualTo(true);
        assertThat(String.valueOf(result.get("action"))).isEqualTo("HOLD");
    }

    @Test
    void enoughValidClosesProduceAnalysis() {
        List<MarketData> bars = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            bars.add(bar(LocalDate.of(2026, 1, 1).plusDays(i), BigDecimal.valueOf(10 + i * 0.1)));
        }
        stubBars(bars);

        Map<String, Object> result = service.analyze(Map.of("stockCode", "000001", "exchange", "SZSE"));

        assertThat(result.get("data_insufficient")).isNotEqualTo(true);
        assertThat(result.get("stock_code")).isEqualTo("000001");
    }

    private void stubBars(List<MarketData> bars) {
        when(marketDataRepository.findLatestTradeDateOnOrBefore(any(LocalDate.class)))
                .thenReturn(Optional.of(bars.get(bars.size() - 1).getTradeDate()));
        when(marketDataRepository.findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                eq("000001"), eq("SZSE"), any(LocalDate.class), any(LocalDate.class))).thenReturn(bars);
    }

    private static MarketData bar(LocalDate date, BigDecimal close) {
        MarketData b = new MarketData();
        b.setStockCode("000001");
        b.setExchangeType("SZSE");
        b.setTradeDate(date);
        b.setClosePrice(close);
        b.setHighPrice(close);
        b.setLowPrice(close);
        b.setVolume(1_000_000L);
        return b;
    }
}
