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
        // 裁定 A（D-R008-03）：信号日不足 2 ⟹ 换手率不可计算，avg/max/min 一律 null（改前为 0.0）
        assertThat(out.get("avg_turnover")).isNull();
        assertThat(out.get("max_turnover")).isNull();
        assertThat(out.get("min_turnover")).isNull();
        assertThat((List<Map<String, Object>>) out.get("daily_turnover")).isEmpty();
    }

    @Test
    void analyzeHandlesNoData() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().analyze(null, d, d, null);
        assertThat(out.get("signal_days")).isEqualTo(0);
        // 无信号日 ⟹ 无定义，null（不得以 0.0 冒充「真的 0 换手」）
        assertThat(out.get("avg_turnover")).isNull();
    }

    @Test
    void identicalConsecutiveDaysStillReportGenuineZeroTurnover() {
        // 防止过度置空：两日候选完全相同 ⟹ 换手率真的为 0，必须是 0.0 而不是 null
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

        assertThat(out.get("turnover_status")).isEqualTo("OK");
        assertThat(out.get("avg_turnover")).isEqualTo(0.0);
        assertThat(out.get("max_turnover")).isEqualTo(0.0);
        assertThat(out.get("min_turnover")).isEqualTo(0.0);
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

        // 退化输入下 avg/max/min 改为 null（无定义），靠 turnover_status 与「真的 0」区分（裁定 A）
        assertThat(out.get("turnover_status")).isEqualTo("INSUFFICIENT_SIGNAL_DAYS");
        assertThat(out.get("avg_turnover")).isNull();
        assertThat(out.get("max_turnover")).isNull();
        assertThat(out.get("min_turnover")).isNull();
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

    // ---- D-R008-01 / 04 / 05：实际规模分母、同策略配对、回显归一（legacy 聚合值零变化） ----

    @SuppressWarnings("unchecked")
    @Test
    void actualDenominatorKeysNeutralizeTheShortHandedDistortion() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        // 集合完全不变但两日各只有 3 只（请求 topN = 10）：
        // legacy（参数分母）读出 1 - 3/10 = 0.7，按实际规模归一应为 0.0。两个数并列给出。
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"), item("R1", 3, "C"),
                        item("R2", 1, "C"), item("R2", 2, "B"), item("R2", 3, "A")
                ));

        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", d1, d2, 10);

        assertThat(out.get("avg_turnover")).isEqualTo(0.7);
        assertThat(out.get("denominator_actual")).isEqualTo("MIN_PREV_CURR_CANDIDATE_COUNT");
        assertThat(out.get("avg_turnover_actual_denominator")).isEqualTo(0.0);
        assertThat(out.get("max_turnover_actual_denominator")).isEqualTo(0.0);
        assertThat(out.get("min_turnover_actual_denominator")).isEqualTo(0.0);
        assertThat(out.get("pairs_dropped_undefined_actual_denominator")).isEqualTo(0);

        List<Map<String, Object>> daily = (List<Map<String, Object>>) out.get("daily_turnover");
        Map<String, Object> row = daily.get(0);
        assertThat(row.get("turnover")).isEqualTo(0.7);
        assertThat(row.get("turnover_actual_denominator")).isEqualTo(0.0);
        assertThat(row.get("denominator_actual_value")).isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sameStrategyPairingExcludesCrossStrategyAndSameDatePairs() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        // 排序后序列 = [CLASSIC|d1, CLASSIC|d2, MOMENTUM|d2]（同日按 createdAt desc 插入序稳定排列）：
        //   对 1 = 同策略、不同交易日 → 计入同策略聚合（1 - 1/2 = 0.5）
        //   对 2 = 不同策略且同一交易日 → 计入 cross_strategy_pairs（1 - 0/2 = 1.0）
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(
                        result("R2", "CLASSIC_SELECTOR", d2), result("R3", "MOMENTUM", d2),
                        result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"),
                        item("R2", 1, "A"), item("R2", 2, "C"),
                        item("R3", 1, "X"), item("R3", 2, "Y")
                ));

        Map<String, Object> out = service().analyze(null, d1, d2, 2);

        assertThat(out.get("pairing_rule")).isEqualTo("SAME_STRATEGY_DISTINCT_TRADING_DAYS");
        assertThat(out.get("same_strategy_pairs")).isEqualTo(1);
        assertThat(out.get("cross_strategy_pairs")).isEqualTo(1);
        // legacy 聚合仍含跨策略对：(0.5 + 1.0) / 2 = 0.75；同策略子集为 0.5
        assertThat(out.get("avg_turnover")).isEqualTo(0.75);
        assertThat(out.get("avg_turnover_same_strategy_only")).isEqualTo(0.5);

        List<Map<String, Object>> daily = (List<Map<String, Object>>) out.get("daily_turnover");
        assertThat(daily.get(0).get("same_strategy_pair")).isEqualTo(true);
        assertThat(daily.get(0).get("same_date_pair")).isEqualTo(false);
        assertThat(daily.get(0).get("prev_strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(daily.get(0).get("curr_strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(daily.get(1).get("same_strategy_pair")).isEqualTo(false);
        assertThat(daily.get(1).get("same_date_pair")).isEqualTo(true);
        assertThat(daily.get(1).get("prev_trade_date")).isEqualTo(d2.toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void strategyEchoIsNormalizedIntoRequestedAndEffectiveKeys() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 1, "A"), item("R1", 2, "B"),
                        item("R2", 1, "A"), item("R2", 2, "B")
                ));

        Map<String, Object> blank = service().analyze("   ", d1, d2, 2);
        Map<String, Object> explicit = service().analyze("CLASSIC_SELECTOR", d1, d2, 2);

        // legacy 键保持历史回显；新键给出「原始请求值 / 实际生效值」这一对，语义不再二义
        assertThat(blank.get("strategy")).isEqualTo("   ");
        assertThat(blank.get("strategy_requested")).isNull();
        assertThat(blank.get("strategy_effective")).isEqualTo("ALL");
        assertThat(explicit.get("strategy_requested")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(explicit.get("strategy_effective")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(blank.get("strategy_echo_note")).isNotNull();
    }

    /** 纯函数边界：分母 ≤ 0（某日候选为空）时「替换率」无定义，必须是 null 而不是 0。 */
    @Test
    void replacementRatioIsUndefinedForNonPositiveDenominator() {
        assertThat(BacktestTurnoverService.replacementRatio(0, 0)).isNull();
        assertThat(BacktestTurnoverService.replacementRatio(3, -1)).isNull();
        assertThat(BacktestTurnoverService.replacementRatio(1, 2)).isEqualTo(0.5);
        assertThat(BacktestTurnoverService.replacementRatio(2, 2)).isEqualTo(0.0);
        // null 透传的取整：null ≠ 0.0
        assertThat(BacktestTurnoverService.round4((Double) null)).isNull();
        assertThat(BacktestTurnoverService.round4(0.0)).isEqualTo(0.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void emptyDayMakesTheActualDenominatorAggregateUndefined() {
        LocalDate d1 = LocalDate.of(2025, 1, 2);
        LocalDate d2 = LocalDate.of(2025, 1, 3);
        // d2 无候选条目 ⟹ min(prev, curr) = 0，该对的替代分母无定义；legacy 仍按参数分母给数
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d1, d2))
                .thenReturn(List.of(result("R2", "CLASSIC_SELECTOR", d2), result("R1", "CLASSIC_SELECTOR", d1)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(item("R1", 1, "A"), item("R1", 2, "B")));

        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", d1, d2, 2);

        assertThat(out.get("avg_turnover")).isEqualTo(1.0); // 1 - 0/2
        assertThat(out.get("avg_turnover_actual_denominator")).isNull();
        assertThat(out.get("pairs_dropped_undefined_actual_denominator")).isEqualTo(1);
        List<Map<String, Object>> daily = (List<Map<String, Object>>) out.get("daily_turnover");
        assertThat(daily.get(0).get("turnover_actual_denominator")).isNull();
        assertThat(daily.get(0).get("denominator_actual_value")).isEqualTo(0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void undefinedBranchKeepsTheSameKeySetAsTheDefinedBranch() {
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

        assertThat(undefined.keySet()).isEqualTo(defined.keySet());
        assertThat(undefined.get("avg_turnover_actual_denominator")).isNull();
        assertThat(undefined.get("same_strategy_pairs")).isEqualTo(0);
        assertThat(undefined.get("pairs_dropped_undefined_actual_denominator")).isNull();
    }
}
