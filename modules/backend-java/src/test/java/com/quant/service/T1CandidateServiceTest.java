package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.T1Candidate;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.T1CandidateRepository;
import com.quant.service.scoring.LimitUpEcologyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * T+1 候选核心闭环：候选生成评分排序 + 次日核销命中率。
 */
@ExtendWith(MockitoExtension.class)
class T1CandidateServiceTest {

    @Mock
    private MarketDataRepository marketDataRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;
    @Mock
    private T1CandidateRepository t1CandidateRepository;
    @Mock
    private LimitUpEcologyService limitUpEcologyService;
    @Mock
    private TradingCalendarService tradingCalendarService;

    private T1CandidateService service() {
        ObjectMapper om = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .build();
        return new T1CandidateService(marketDataRepository, stockInfoRepository, t1CandidateRepository,
                limitUpEcologyService, tradingCalendarService, om);
    }

    @Test
    void generateCandidatesScoresAndRanksTopN() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        List<MarketData> rangeBars = rangeBars("000001", "SZSE", d, 35);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rangeBars);
        when(marketDataRepository.findByTradeDate(d)).thenReturn(List.of(rangeBars.get(rangeBars.size() - 1)));
        StockInfo info = new StockInfo();
        info.setStockCode("000001");
        info.setExchangeType("SZSE");
        info.setStockName("测试股份");
        info.setListDate(d.minusDays(500));
        when(stockInfoRepository.findAll()).thenReturn(List.of(info));
        when(limitUpEcologyService.stockLimitUpFeatures(any(), any(), any()))
                .thenReturn(Map.of("is_limit_up", true, "lianban_days", 1,
                        "volume_ratio", 2.0, "turnover_rate", 5.0, "change_pct", 10.0));
        when(limitUpEcologyService.ecology(any(), anyInt())).thenReturn(Map.of("limit_up_count", 1));
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.empty());

        Map<String, Object> out = service().generateCandidates(d, 5);

        assertThat(out.get("top_n")).isEqualTo(1); // 仅 1 只可交易标的
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) out.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("stock_code")).isEqualTo("000001");
        assertThat(candidates.get(0).get("score_type")).isEqualTo("rule_score");
        assertThat((double) candidates.get(0).get("score")).isGreaterThan(0);
    }

    @Test
    void settleCandidatesComputesHitRate() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        LocalDate d1 = LocalDate.of(2025, 1, 3);
        // 候选 JSON（1 只，T 日收盘 10）
        T1Candidate rec = new T1Candidate();
        rec.setTradeDate(d);
        rec.setTopN(1);
        rec.setCandidatesJson("[{\"stock_code\":\"000001\",\"exchange_type\":\"SZSE\",\"stock_name\":\"测试\","
                + "\"score\":70.0,\"close\":10.0,\"is_limit_up\":false,\"lianban_days\":0}]");
        when(t1CandidateRepository.findByTradeDate(d)).thenReturn(Optional.of(rec));
        when(tradingCalendarService.findTradingDays(d, d.plusDays(7)))
                .thenReturn(List.of(d, d1));
        // 次日收盘 11 → +10% → hit
        when(marketDataRepository.findByTradeDate(d1))
                .thenReturn(List.of(nextBar(d1, "000001", "SZSE", 11.0, 10.0)));

        Map<String, Object> out = service().settleCandidates(d);

        assertThat(out.get("next_trade_date")).isEqualTo(d1.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(((Number) summary.get("hit_count")).intValue()).isEqualTo(1);
        assertThat(((Number) summary.get("hit_rate")).doubleValue()).isEqualTo(1.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertThat(((Number) results.get(0).get("next_ret")).doubleValue()).isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    private static List<MarketData> rangeBars(String code, String ex, LocalDate end, int days) {
        List<MarketData> bars = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = end.minusDays((days - 1 - i) * 1L);
            double close = 10 + i * 0.1;
            MarketData m = new MarketData();
            m.setStockCode(code);
            m.setExchangeType(ex);
            m.setTradeDate(date);
            m.setClosePrice(BigDecimal.valueOf(close));
            m.setPreClose(BigDecimal.valueOf(i == 0 ? close : close - 0.1));
            m.setOpenPrice(BigDecimal.valueOf(close));
            m.setHighPrice(BigDecimal.valueOf(close + 0.2));
            m.setLowPrice(BigDecimal.valueOf(close - 0.2));
            m.setVolume(1_000_000L);
            m.setAmount(BigDecimal.valueOf(close * 3_000_000)); // 日均成交额 ≈30M+，通过 minAvgAmount 闸
            bars.add(m);
        }
        return bars;
    }

    private static MarketData nextBar(LocalDate date, String code, String ex, double close, double preClose) {
        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setClosePrice(BigDecimal.valueOf(close));
        m.setOpenPrice(BigDecimal.valueOf(close * 0.99));
        m.setPreClose(BigDecimal.valueOf(preClose));
        m.setHighPrice(BigDecimal.valueOf(close * 1.02));
        m.setLowPrice(BigDecimal.valueOf(close * 0.98));
        m.setVolume(1_000_000L);
        return m;
    }
}
