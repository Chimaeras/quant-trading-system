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

    // ---- Q-R008-02/03/04/05/06/07/09：增量披露字段（数值与既有键零变化） ----

    @SuppressWarnings("unchecked")
    @Test
    void shortHandedDaysExposeActualSizesAndTheRequestParamDenominator() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        // 双侧短员：两日各 3 条，请求 topN = 10。分母是请求参数 ⟹ 交集 3 → turnover = 1 - 3/10 = 0.7，
        // 而「按当日实际规模归一」的口径应为 0.0。本条只证明分母可被外部直接读出并复算，不改数值。
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"), item("R1", 3, "C"),
                        item("R2", 1, "A"), item("R2", 2, "B"), item("R2", 3, "C")
                ));

        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", d1, d2, 10);

        assertThat(out.get("avg_turnover")).isEqualTo(0.7);
        assertThat(out.get("denominator")).isEqualTo("REQUEST_PARAM_TOP_N");
        List<Map<String, Object>> daily = (List<Map<String, Object>>) out.get("daily_turnover");
        Map<String, Object> row = daily.get(0);
        assertThat(row.get("prev_size")).isEqualTo(3);
        assertThat(row.get("curr_size")).isEqualTo(3);
        assertThat(row.get("intersection_count")).isEqualTo(3);
        assertThat(row.get("short_handed")).isEqualTo(true);
        // 读者可用披露的三元组精确复算该日 turnover（不依赖任何未发射的量）
        int n = (int) out.get("top_n");
        double recomputed = 1.0 - (int) row.get("intersection_count") / (double) n;
        assertThat((double) row.get("turnover")).isEqualTo(Math.round(recomputed * 10000.0) / 10000.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fullRosterDaysAreNotFlaggedShortHanded() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"),
                        item("R2", 1, "A"), item("R2", 2, "B")
                ));

        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", d1, d2, 2);

        List<Map<String, Object>> daily = (List<Map<String, Object>>) out.get("daily_turnover");
        assertThat(daily.get(0).get("short_handed")).isEqualTo(false);
        assertThat(daily.get(0).get("turnover")).isEqualTo(0.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void disclosureKeysAreEmittedInTheUndefinedBranchToo() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", d)));

        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", d, d, 3);

        // 退化输入下 avg/max/min 仍是历史行为的 0.0，但新增机器可读标记与「真的 0」区分
        assertThat(out.get("turnover_status")).isEqualTo("INSUFFICIENT_SIGNAL_DAYS");
        assertThat(out.get("avg_turnover")).isEqualTo(0.0);
        assertThat(out.get("turnover_basis")).isEqualTo("ONE_WAY_REPLACEMENT_RATIO");
        assertThat(out.get("denominator")).isEqualTo("REQUEST_PARAM_TOP_N");
        assertThat(out.get("avg_turnover_unit")).isEqualTo("RATIO_0_1");
        assertThat(out.get("rounding_rule")).isEqualTo("ROUND_HALF_UP_4DP");
        assertThat(out.get("strategy_filter")).isEqualTo("EXACT_STRATEGY");
        assertThat(out.get("grouped_by_strategy")).isEqualTo(true);
    }

    @SuppressWarnings("unchecked")
    @Test
    void definedAndUndefinedBranchesEmitTheSameDisclosureKeySet() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"),
                        item("R2", 1, "B"), item("R2", 2, "C")
                ));
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d2, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2)));

        Map<String, Object> defined = service().analyze("CLASSIC_SELECTOR", d1, d2, 2);
        Map<String, Object> undefined = service().analyze("CLASSIC_SELECTOR", d2, d2, 2);

        assertThat(defined.get("turnover_status")).isEqualTo("OK");
        assertThat(undefined.get("turnover_status")).isEqualTo("INSUFFICIENT_SIGNAL_DAYS");
        assertThat(undefined.keySet()).isEqualTo(defined.keySet());
    }

    @SuppressWarnings("unchecked")
    @Test
    void strategyFilterEchoReflectsTheActualFilteringMode() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"),
                        item("R2", 1, "A"), item("R2", 2, "B")
                ));

        Map<String, Object> blank = service().analyze("", d1, d2, 2);
        Map<String, Object> explicit = service().analyze("CLASSIC_SELECTOR", d1, d2, 2);

        // "" 与 null 走同一分支、计算等价，但回显不同（Q-R008-05）：载荷以策略过滤语义键把这点写明
        assertThat(blank.get("strategy")).isEqualTo("");
        assertThat(blank.get("strategy_filter")).isEqualTo("NONE_ALL_STRATEGIES");
        assertThat(blank.get("grouped_by_strategy")).isEqualTo(false);
        assertThat(explicit.get("strategy_filter")).isEqualTo("EXACT_STRATEGY");
        assertThat(blank.get("avg_turnover")).isEqualTo(explicit.get("avg_turnover"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void crossStrategyPairingIsDisclosedWhenStrategyIsNull() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        // 同一天两个策略：null 路径不按策略分组，因此 signal_days = 4（> 窗口内不同日期数 2）
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(
                        result("R4", "MOMENTUM", d2), result("R2", "CLASSIC_SELECTOR", d2),
                        result("R3", "MOMENTUM", d1), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"),
                        item("R2", 1, "C"), item("R2", 2, "D"),
                        item("R3", 1, "A"), item("R3", 2, "B"),
                        item("R4", 1, "C"), item("R4", 2, "D")
                ));

        Map<String, Object> out = service().analyze(null, d1, d2, 2);

        assertThat(out.get("signal_days")).isEqualTo(4);
        assertThat(out.get("strategy")).isEqualTo("ALL");
        assertThat(out.get("grouped_by_strategy")).isEqualTo(false);
        assertThat(out.get("strategy_filter")).isEqualTo("NONE_ALL_STRATEGIES");
        assertThat((String) out.get("strategy_filter_note")).contains("不按策略分组");
    }
}
