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
class BacktestTurnoverServiceTest {

    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private SelectorResultItemRepository selectorResultItemRepository;

    private BacktestTurnoverService service() {
        return new BacktestTurnoverService(selectorResultRepository, selectorResultItemRepository);
    }

    private static SelectorResult result(String resultId, String strategy, LocalDate tradeDate) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(tradeDate);
        return r;
    }

    private static SelectorResultItem item(String resultId, int rank, String code) {
        SelectorResultItem it = new SelectorResultItem();
        it.setResultId(resultId);
        it.setRankNo(rank);
        it.setStockCode(code);
        it.setExchangeType("SZSE");
        return it;
    }

    @SuppressWarnings("unchecked")
    @Test
    void analyzeComputesTurnoverBetweenDays() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        // day1: A B C ; day2: A B D → 交集 2，turnover = 1 - 2/3 = 0.3333
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"), item("R1", 3, "C"),
                        item("R2", 1, "A"), item("R2", 2, "B"), item("R2", 3, "D")
                ));

        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", d1, d2, 3);

        assertThat(out.get("signal_days")).isEqualTo(2);
        assertThat(out.get("avg_turnover")).isEqualTo(0.3333);
        List<Map<String, Object>> daily = (List<Map<String, Object>>) out.get("daily_turnover");
        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).get("new_count")).isEqualTo(1);  // D 新进
        assertThat(daily.get(0).get("exit_count")).isEqualTo(1); // C 退出
    }

    @SuppressWarnings("unchecked")
    @Test
    void analyzeReturnsEmptyWhenSingleDay() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", d)));

        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", d, d, 10);
        assertThat(out.get("signal_days")).isEqualTo(1);
        assertThat(out.get("avg_turnover")).isEqualTo(0.0);
        assertThat((List<Map<String, Object>>) out.get("daily_turnover")).isEmpty();
    }

    @Test
    void analyzeHandlesNoData() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().analyze(null, d, d, null);
        assertThat(out.get("signal_days")).isEqualTo(0);
        assertThat(out.get("avg_turnover")).isEqualTo(0.0);
    }
}
