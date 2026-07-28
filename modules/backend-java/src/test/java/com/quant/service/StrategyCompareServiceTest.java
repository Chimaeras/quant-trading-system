package com.quant.service;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
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
class StrategyCompareServiceTest {

    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private SelectorResultItemRepository selectorResultItemRepository;

    private StrategyCompareService service() {
        return new StrategyCompareService(selectorResultRepository, selectorResultItemRepository);
    }

    private static SelectorResult result(String resultId, String strategy, LocalDate tradeDate) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(tradeDate);
        r.setFactorVersion(strategy.toLowerCase() + "_v1");
        return r;
    }

    private static SelectorResultItem item(String resultId, int rank, String code) {
        SelectorResultItem it = new SelectorResultItem();
        it.setResultId(resultId);
        it.setRankNo(rank);
        it.setStockCode(code);
        it.setExchangeType("SZSE");
        it.setStockName("测试" + code);
        it.setFinalScore(80.0 - rank);
        return it;
    }

    @Test
    void compareReturnsEmptyWhenNoResults() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(d))
                .thenReturn(List.of());

        Map<String, Object> out = service().compare(d, 10);

        assertThat(out.get("strategy_count")).isEqualTo(0);
        assertThat(out.get("strategies")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("overlap_summary");
        assertThat(summary.get("verdict")).asString().contains("无成功选股结果");
    }

    @Test
    void compareDetectsOverlapAcrossStrategies() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        SelectorResult classic = result("R1", "CLASSIC_SELECTOR", d);
        SelectorResult trend = result("R2", "TREND", d);
        when(selectorResultRepository.findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(d))
                .thenReturn(List.of(classic, trend));
        // classic: 000001 / 000002 / 000003
        // trend:   000001 / 000004
        // 重合：000001（2 个策略）
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "000001"),
                        item("R1", 2, "000002"),
                        item("R1", 3, "000003"),
                        item("R2", 1, "000001"),
                        item("R2", 2, "000004")
                ));

        Map<String, Object> out = service().compare(d, 10);

        assertThat(out.get("strategy_count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strategies = (List<Map<String, Object>>) out.get("strategies");
        assertThat(strategies).hasSize(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overlap = (List<Map<String, Object>>) out.get("overlap_stocks");
        assertThat(overlap).hasSize(1);
        assertThat(overlap.get(0).get("stock_code")).isEqualTo("000001");
        assertThat(overlap.get(0).get("hit_count")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("overlap_summary");
        assertThat(summary.get("unique_count")).isEqualTo(4);
        assertThat(summary.get("overlap_count")).isEqualTo(1);
        assertThat(summary.get("overlap_ratio")).isEqualTo(25.0);
    }
}
