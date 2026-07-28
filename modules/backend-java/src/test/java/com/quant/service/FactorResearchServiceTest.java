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
class FactorResearchServiceTest {

    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private SelectorResultItemRepository selectorResultItemRepository;

    private FactorResearchService service() {
        return new FactorResearchService(selectorResultRepository, selectorResultItemRepository);
    }

    private static SelectorResult result(String resultId, String strategy, LocalDate tradeDate) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(tradeDate);
        return r;
    }

    private static SelectorResultItem item(String resultId, double score) {
        SelectorResultItem it = new SelectorResultItem();
        it.setResultId(resultId);
        it.setFinalScore(score);
        return it;
    }

    @Test
    void researchComputesCoverageAndDistribution() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", d)));
        // 4 个候选，1 个 score=0（视为无效），3 个有效：60/70/80
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 60.0),
                        item("R1", 70.0),
                        item("R1", 80.0),
                        item("R1", 0.0)
                ));

        Map<String, Object> out = service().research("CLASSIC_SELECTOR", d, d);

        assertThat(out.get("strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(out.get("total_observations")).isEqualTo(4);
        assertThat(out.get("valid_observations")).isEqualTo(3);
        assertThat(out.get("coverage")).isEqualTo(75.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> dist = (Map<String, Object>) out.get("distribution");
        assertThat(dist.get("min")).isEqualTo(60.0);
        assertThat(dist.get("max")).isEqualTo(80.0);
        assertThat(dist.get("median")).isEqualTo(70.0);
    }

    @Test
    void researchReturnsZerosWhenNoData() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().research(null, d, d);

        assertThat(out.get("total_observations")).isEqualTo(0);
        assertThat(out.get("coverage")).isEqualTo(0.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> dist = (Map<String, Object>) out.get("distribution");
        assertThat(dist.get("median")).isEqualTo(0.0);
    }
}
