package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.service.scoring.ScoringConstants;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OversoldReboundScoringServiceTest {

    private final OversoldReboundScoringService service = new OversoldReboundScoringService(
            mock(com.quant.repository.MarketDataRepository.class),
            mock(com.quant.repository.StockInfoRepository.class),
            mock(com.quant.repository.SelectorResultRepository.class),
            mock(com.quant.repository.SelectorResultItemRepository.class),
            mock(com.quant.repository.SelectorTemplateRepository.class),
            mock(com.quant.service.scoring.ProgressiveResultWriter.class)
    );

    @Test
    void excludesCandidateBelowMinimumAverageAmount() {
        List<MarketData> bars = sampleBars(ScoringConstants.MIN_BARS_OVERSOLD + 10, 10, 500_000);
        LocalDate tradeDate = bars.get(bars.size() - 1).getTradeDate();

        var result = service.scoreOne(
                "000001",
                "SZSE",
                tradeDate,
                bars,
                new HashMap<>(),
                true,
                1_000_000,
                1,
                null,
                0
        );

        assertThat(result).isNull();
    }

    @Test
    void excludesCandidateWhenListingTenureTooShortWithoutListDate() {
        List<MarketData> bars = sampleBars(ScoringConstants.MIN_BARS_OVERSOLD + 10, 10, 50_000_000);
        LocalDate tradeDate = bars.get(bars.size() - 1).getTradeDate();

        var result = service.scoreOne(
                "000001",
                "SZSE",
                tradeDate,
                bars,
                new HashMap<>(Map.of("000001:SZSE", "测试股份")),
                true,
                1_000_000,
                1,
                null,
                300
        );

        assertThat(result).isNull();
    }

    @Test
    void allowsCandidateAtOrAboveMinimumAverageAmount() {
        List<MarketData> bars = sampleBars(ScoringConstants.MIN_BARS_OVERSOLD + 10, 10, 50_000_000);
        LocalDate tradeDate = bars.get(bars.size() - 1).getTradeDate();

        var result = service.scoreOne(
                "000001",
                "SZSE",
                tradeDate,
                bars,
                new HashMap<>(Map.of("000001:SZSE", "测试股份")),
                true,
                1_000_000,
                1,
                bars.get(0).getTradeDate(),
                60
        );

        assertThat(result).isNotNull();
    }

    @Test
    void excludesStCandidateWhenExcludeStEnabled() {
        List<MarketData> bars = sampleBars(ScoringConstants.MIN_BARS_OVERSOLD + 10, 10, 50_000_000);
        LocalDate tradeDate = bars.get(bars.size() - 1).getTradeDate();

        // excludeST=true 时，名称含 ST 的标的应被排除
        var excluded = service.scoreOne(
                "000001",
                "SZSE",
                tradeDate,
                bars,
                new HashMap<>(Map.of("000001:SZSE", "*ST测试")),
                true,
                1_000_000,
                1,
                bars.get(0).getTradeDate(),
                60
        );
        assertThat(excluded).isNull();

        // excludeST=false 时，ST 标的可进入评分（涨跌停口径仍按板块解析）
        var allowed = service.scoreOne(
                "000001",
                "SZSE",
                tradeDate,
                bars,
                new HashMap<>(Map.of("000001:SZSE", "*ST测试")),
                false,
                1_000_000,
                1,
                bars.get(0).getTradeDate(),
                60
        );
        assertThat(allowed).isNotNull();
    }

    private static List<MarketData> sampleBars(int count, double close, double amount) {
        List<MarketData> bars = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2023, 1, 1);
        for (int i = 0; i < count; i++) {
            MarketData bar = new MarketData();
            bar.setStockCode("000001");
            bar.setExchangeType("SZSE");
            bar.setTradeDate(start.plusDays(i));
            double price = close + i * 0.01;
            bar.setOpenPrice(BigDecimal.valueOf(price));
            bar.setClosePrice(BigDecimal.valueOf(price));
            bar.setAmount(BigDecimal.valueOf(amount));
            bar.setVolume(1_000_000L);
            bars.add(bar);
        }
        return bars;
    }
}
