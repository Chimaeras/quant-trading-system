package com.quant.service.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.UnifiedScoringTaskRepository;
import com.quant.service.MultiDimScoringService;
import com.quant.service.OversoldReboundScoringService;
import com.quant.service.StockSelectorService;
import com.quant.service.StockTrendScoringService;
import com.quant.service.scoring.ComprehensiveScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归测试：UnifiedScoringService 写入 factor_snapshot / 输出的 tradability.bulk_fetch
 * 必须反映请求实际 bulkFetch 口径，且 MULTI_DIM 强制为 true（与各子服务行为一致）。
 */
class UnifiedScoringServiceTradabilityTest {

    private final StockTrendScoringService trendScoringService = mock(StockTrendScoringService.class);
    private final MultiDimScoringService multiDimScoringService = mock(MultiDimScoringService.class);

    private UnifiedScoringService newService() {
        return new UnifiedScoringService(
                trendScoringService,
                mock(ComprehensiveScoringService.class),
                multiDimScoringService,
                mock(StockSelectorService.class),
                mock(OversoldReboundScoringService.class),
                mock(SelectorResultRepository.class),
                mock(SelectorResultItemRepository.class),
                mock(UnifiedScoringTaskRepository.class),
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class),
                mock(PlatformTransactionManager.class)
        );
    }

    private static Map<String, Object> cannedResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", List.of());
        r.put("trade_date", "2024-06-03");
        r.put("factor_version", "trend");
        return r;
    }

    @Test
    void tradabilityBulkFetchDefaultsToTrueWhenNotSpecified() {
        when(trendScoringService.run(any())).thenReturn(cannedResult());
        UnifiedScoringService service = newService();

        Map<String, Object> out = service.run(Map.of("strategy", "TREND", "persist", false));

        @SuppressWarnings("unchecked")
        Map<String, Object> tradability = (Map<String, Object>) out.get("tradability");
        assertThat(tradability.get("bulk_fetch")).isEqualTo(true);
        assertThat(tradability.get("listing_tenure_bar_scope"))
                .isEqualTo("FULL_BULK_BARS_IN_LOOKBACK");
        assertThat(tradability).doesNotContainKey("listing_tenure_warning");
    }

    @Test
    void tradabilityBulkFetchReflectsExplicitFalseFromRequest() {
        when(trendScoringService.run(any())).thenReturn(cannedResult());
        UnifiedScoringService service = newService();

        Map<String, Object> out = service.run(Map.of(
                "strategy", "TREND",
                "persist", false,
                "bulkFetch", false
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> tradability = (Map<String, Object>) out.get("tradability");
        assertThat(tradability.get("bulk_fetch")).isEqualTo(false);
        assertThat(tradability.get("listing_tenure_bar_scope"))
                .isEqualTo("LOOKBACK_WINDOW_EARLIEST_TRADE_DATE_ONLY");
        assertThat(tradability.get("listing_tenure_warning")).isNotNull();
    }

    @Test
    void multiDimForcesBulkFetchTrueEvenWhenRequestDisablesIt() {
        when(multiDimScoringService.run(any())).thenReturn(cannedResult());
        UnifiedScoringService service = newService();

        Map<String, Object> out = service.run(Map.of(
                "strategy", "MULTI_DIM",
                "persist", false,
                "bulkFetch", false
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> tradability = (Map<String, Object>) out.get("tradability");
        assertThat(tradability.get("bulk_fetch")).isEqualTo(true);
        assertThat(tradability.get("listing_tenure_bar_scope"))
                .isEqualTo("FULL_BULK_BARS_IN_LOOKBACK");
    }

    /**
     * QP-002：统一打分响应必须标注 score_type=rule_score，明确输出是规则分数/截面百分位，
     * 不是上涨概率；prob_up 待概率模型校准。避免前端/接口误读。
     */
    @Test
    void responseMarksScoreTypeAsRuleScoreNotProbability() {
        when(trendScoringService.run(any())).thenReturn(cannedResult());
        UnifiedScoringService service = newService();

        Map<String, Object> out = service.run(Map.of("strategy", "TREND", "persist", false));

        assertThat(out.get("score_type")).isEqualTo("rule_score");
        assertThat((String) out.get("score_type_note")).contains("非上涨概率");
    }
}