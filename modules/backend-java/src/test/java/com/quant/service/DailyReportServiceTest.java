package com.quant.service;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.StockInfo;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockInfoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyReportServiceTest {

    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private SelectorResultItemRepository selectorResultItemRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;

    private DailyReportService service() {
        return new DailyReportService(selectorResultRepository, selectorResultItemRepository, stockInfoRepository);
    }

    private static SelectorResult result(String resultId, String strategy, LocalDate tradeDate, int returned) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(tradeDate);
        r.setRunStatus("SUCCESS");
        r.setFactorVersion(strategy.toLowerCase() + "_v1");
        r.setTotalCandidates(100);
        r.setSuccessCandidates(returned);
        return r;
    }

    private static SelectorResultItem item(String resultId, int rank, String code, String name, double score) {
        SelectorResultItem it = new SelectorResultItem();
        it.setResultId(resultId);
        it.setRankNo(rank);
        it.setStockCode(code);
        it.setExchangeType("SZSE");
        it.setStockName(name);
        it.setFinalScore(score);
        return it;
    }

    private static StockInfo stockInfo(String code, String exchange, String industry) {
        StockInfo s = new StockInfo();
        s.setStockCode(code);
        s.setExchangeType(exchange);
        s.setIndustry(industry);
        return s;
    }

    @Test
    void dailyReportReturnsEmptyWhenNoResults() {
        when(selectorResultRepository.findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(any()))
                .thenReturn(List.of());

        Map<String, Object> out = service().buildDailyReport(LocalDate.of(2025, 1, 2));

        assertThat(out.get("has_any_result")).isEqualTo(false);
        assertThat(out.get("trade_date")).isEqualTo("2025-01-02");
        assertThat(out.get("strategies")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("overall_summary");
        assertThat(summary.get("strategy_count")).isEqualTo(0);
    }

    @Test
    void dailyReportAggregatesStrategiesAndMarksNoOpportunity() {
        LocalDate tradeDate = LocalDate.of(2025, 1, 2);
        SelectorResult classic = result("R1", "CLASSIC_SELECTOR", tradeDate, 3);
        SelectorResult oversold = result("R2", "OVERSOLD_REBOUND", tradeDate, 0);

        when(selectorResultRepository.findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(tradeDate))
                .thenReturn(List.of(classic, oversold));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(List.of("R1", "R2")))
                .thenReturn(List.of(
                        item("R1", 1, "000001", "测试A", 80.0),
                        item("R1", 2, "000002", "测试B", 75.0),
                        item("R1", 3, "000003", "测试C", 60.0)
                ));
        // 行业信息：前两只同属「银行」，第三只「地产」
        when(stockInfoRepository.findByStockKeyIn(any())).thenReturn(List.of(
                stockInfo("000001", "SZSE", "银行"),
                stockInfo("000002", "SZSE", "银行"),
                stockInfo("000003", "SZSE", "地产")
        ));

        Map<String, Object> out = service().buildDailyReport(tradeDate);

        assertThat(out.get("has_any_result")).isEqualTo(true);
        assertThat(out.get("trade_date")).isEqualTo("2025-01-02");
        assertThat(out.get("data_source")).isEqualTo("persisted_selector_result");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strategies = (List<Map<String, Object>>) out.get("strategies");
        assertThat(strategies).hasSize(2);

        Map<String, Object> classicSection = strategies.stream()
                .filter(s -> "CLASSIC_SELECTOR".equals(s.get("strategy"))).findFirst().orElseThrow();
        assertThat(classicSection.get("has_opportunity")).isEqualTo(true);
        assertThat(classicSection.get("returned_items")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topItems = (List<Map<String, Object>>) classicSection.get("top_items");
        assertThat(topItems).hasSize(3);
        assertThat(topItems.get(0).get("stock_code")).isEqualTo("000001");

        Map<String, Object> oversoldSection = strategies.stream()
                .filter(s -> "OVERSOLD_REBOUND".equals(s.get("strategy"))).findFirst().orElseThrow();
        assertThat(oversoldSection.get("has_opportunity")).isEqualTo(false);
        assertThat(oversoldSection.get("returned_items")).isEqualTo(0);
        assertThat(oversoldSection.get("top_items")).isEqualTo(List.of());

        // 行业集中度：classic 3 个候选，银行 2 个 = 66.7%，应触发告警
        @SuppressWarnings("unchecked")
        Map<String, Object> conc = (Map<String, Object>) classicSection.get("industry_concentration");
        assertThat(conc.get("total")).isEqualTo(3);
        assertThat(conc.get("max_concentration_pct")).isEqualTo(66.67);
        assertThat(conc.get("warning")).asString().contains("银行");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> industries = (List<Map<String, Object>>) conc.get("industries");
        assertThat(industries.get(0).get("industry")).isEqualTo("银行");
        assertThat(industries.get(0).get("count")).isEqualTo(2);

        // oversold 无候选，集中度 total=0、无告警
        @SuppressWarnings("unchecked")
        Map<String, Object> oversoldConc = (Map<String, Object>) oversoldSection.get("industry_concentration");
        assertThat(oversoldConc.get("total")).isEqualTo(0);
        assertThat(oversoldConc.get("warning")).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("overall_summary");
        assertThat(summary.get("strategy_count")).isEqualTo(2);
        assertThat(summary.get("strategies_with_opportunity")).isEqualTo(1L);
    }

    @Test
    void dailyReportDefaultsToLatestPerStrategyWhenNoDateGiven() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        SelectorResult classic = result("R1", "CLASSIC_SELECTOR", d2, 2);
        SelectorResult trend = result("R2", "TREND", d1, 1);

        when(selectorResultRepository.findLatestSuccessPerStrategy())
                .thenReturn(List.of(classic, trend));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(item("R1", 1, "000001", "A", 80.0)));
        when(stockInfoRepository.findByStockKeyIn(any())).thenReturn(List.of());

        Map<String, Object> out = service().buildDailyReport(null);

        // 报告数据日期取各策略最新中的最大值
        assertThat(out.get("trade_date")).isEqualTo("2025-01-03");
        assertThat(out.get("has_any_result")).isEqualTo(true);
    }

    @SuppressWarnings("unchecked")
    @Test
    void trendReportAggregatesDailyCandidateCounts() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(
                        result("R2", "CLASSIC_SELECTOR", d2, 5),
                        result("R1", "CLASSIC_SELECTOR", d1, 3)
                ));

        Map<String, Object> out = service().buildTrendReport(d1, d2);

        assertThat(out.get("signal_days")).isEqualTo(2);
        assertThat((List<String>) out.get("strategies")).containsExactly("CLASSIC_SELECTOR");

        List<Map<String, Object>> trend = (List<Map<String, Object>>) out.get("daily_trend");
        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).get("trade_date")).isEqualTo("2025-01-02");
        assertThat(trend.get(0).get("total_candidates")).isEqualTo(3);
        assertThat(trend.get(1).get("trade_date")).isEqualTo("2025-01-03");
        assertThat(trend.get(1).get("total_candidates")).isEqualTo(5);
    }

    @Test
    void trendReportReturnsEmptyWhenNoData() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().buildTrendReport(d, d);
        assertThat(out.get("signal_days")).isEqualTo(0);
        assertThat((List<?>) out.get("daily_trend")).isEmpty();
    }
}
