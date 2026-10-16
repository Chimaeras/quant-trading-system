package com.quant.service.scoring.factor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 D 测试：策略目录元数据。
 *
 * <p>核心主张：<b>「5 个策略」现在只有一处定义</b>。本测试把这条主张变成可执行的断言——
 * 若有人新增策略却忘了给 UI 入口、或让两个策略抢同一个 tab、或把权重写成了空，
 * 这里就会失败。
 */
class StrategyCatalogServiceTest {

    private final StrategyCatalogService service = new StrategyCatalogService();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> weightsOf(Map<String, Object> strategy) {
        return (List<Map<String, Object>>) strategy.get("weights");
    }

    @Test
    void exposesExactlyTheFiveStrategiesThatTheCatalogDeclares() {
        List<Map<String, Object>> list = service.listStrategies();

        assertThat(list).hasSize(5);
        assertThat(list).extracting(m -> m.get("legacy_strategy_type"))
                .containsExactly("CLASSIC_SELECTOR", "TREND", "OVERSOLD_REBOUND",
                        "COMPREHENSIVE", "MULTI_DIM");
    }

    @Test
    void everyStrategyMapsToExactlyOneDistinctUiTab() {
        List<Map<String, Object>> list = service.listStrategies();

        List<Object> modes = new ArrayList<>();
        for (Map<String, Object> m : list) {
            modes.add(m.get("legacy_ui_mode"));
        }

        // 五个策略必须都有 UI 入口（保留前端 5 个入口是既定约束）
        assertThat(modes).doesNotContainNull();
        // 且不得有两个策略抢同一个 tab
        assertThat(new LinkedHashSet<>(modes)).hasSize(modes.size());
        assertThat(modes).containsExactly("classic", "trend", "oversold",
                "comprehensive", "multi_dim");
    }

    @Test
    void uiModeKeysMatchTheFrontendContract() {
        // 与 StockPicker.vue 的 ModeType 对齐。写成显式集合，
        // 这样前端改了 tab 键而目录没跟上时能被发现（而不是静默对不上）。
        Set<String> frontendModeKeys = Set.of(
                "classic", "oversold", "trend", "comprehensive", "multi_dim");

        for (Map<String, Object> m : service.listStrategies()) {
            assertThat(frontendModeKeys)
                    .as("策略 %s 的 ui mode 必须属于前端已知 tab 集合", m.get("legacy_strategy_type"))
                    .contains(String.valueOf(m.get("legacy_ui_mode")));
        }
    }

    @Test
    void everyStrategyExposesLabeledNormalizedWeights() {
        for (Map<String, Object> m : service.listStrategies()) {
            String code = String.valueOf(m.get("code"));
            List<Map<String, Object>> weights = weightsOf(m);

            assertThat(weights).as("策略 %s 必须有因子权重", code).isNotEmpty();

            double absSum = 0;
            for (Map<String, Object> w : weights) {
                assertThat(w.get("label")).as("因子 %s 需要中文标签供前端展示", w.get("factor"))
                        .isNotNull();
                assertThat(String.valueOf(w.get("label"))).isNotBlank();
                assertThat(w.get("normalized_weight")).as("需要归一化权重").isNotNull();
                assertThat(w.get("fundamental")).isNotNull();
                assertThat(w.get("risk")).isNotNull();
                absSum += Math.abs(((Number) w.get("normalized_weight")).doubleValue());
            }
            // 归一化后绝对值之和应为 1（浮点容差）
            assertThat(absSum).as("策略 %s 的归一化权重绝对值之和", code)
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void strategiesThatDependOnFundamentalsAreFlaggedAsSuch() {
        Map<String, Map<String, Object>> byType = new java.util.LinkedHashMap<>();
        for (Map<String, Object> m : service.listStrategies()) {
            byType.put(String.valueOf(m.get("legacy_strategy_type")), m);
        }

        // CLASSIC / TREND / OVERSOLD 不含基本面因子
        for (String code : List.of("CLASSIC_SELECTOR", "TREND", "OVERSOLD_REBOUND")) {
            assertThat(byType.get(code).get("fundamental_dependent"))
                    .as("%s 不应依赖基本面", code).isEqualTo(false);
            assertThat(((Number) byType.get(code).get("non_fundamental_weight_share")).doubleValue())
                    .as("%s 的非基本面权重占比", code).isEqualTo(1.0);
        }

        // COMPREHENSIVE / MULTI_DIM 含估值因子 → 标记为依赖基本面。
        // 当前库 PE/PB 与财务快照填充率为 0，这个标记就是缺口的前置告警。
        for (String code : List.of("COMPREHENSIVE", "MULTI_DIM")) {
            assertThat(byType.get(code).get("fundamental_dependent"))
                    .as("%s 依赖基本面", code).isEqualTo(true);
            assertThat(((Number) byType.get(code).get("non_fundamental_weight_share")).doubleValue())
                    .as("%s 的非基本面权重占比应小于 1", code).isLessThan(1.0);
        }
    }

    @Test
    void describeOneReturnsNullForUnknownStrategy() {
        assertThat(service.describeOne("NO_SUCH_STRATEGY")).isNull();
        assertThat(service.describeOne(null)).isNull();
        // 大小写与别名应被目录统一处理
        assertThat(service.describeOne("classic_selector")).isNotNull();
    }

    @Test
    void responseCarriesExactlyTheFieldsTheFrontendReads() {
        // 前端 ScoringStrategyMeta 依赖下列键名。把它们钉死，
        // 这样后端改键名时是"测试失败"而不是"页面静默少一列"。
        Map<String, Object> one = service.describeOne("COMPREHENSIVE");

        assertThat(one).containsKeys(
                "code",
                "display_name",
                "factor_version",
                "legacy_strategy_type",
                "legacy_ui_mode",
                "min_bars",
                "min_avg_amount",
                "min_listing_days",
                "missing_data_policy",
                "min_available_weight_ratio",
                "require_ma20_above_ma60",
                "weights",
                "required_factors",
                "non_fundamental_weight_share",
                "fundamental_dependent");

        assertThat(one.get("legacy_strategy_type")).isEqualTo("COMPREHENSIVE");
        assertThat(one.get("legacy_ui_mode")).isEqualTo("comprehensive");
        assertThat(one.get("display_name")).isEqualTo("综合三维度");

        // 权重条目的键名同样是前端契约
        for (Map<String, Object> w : weightsOf(one)) {
            assertThat(w).containsKeys(
                    "factor", "label", "weight", "normalized_weight", "fundamental", "risk");
        }
    }

    @Test
    void describeOneAgreesWithTheList() {
        Map<String, Object> one = service.describeOne("TREND");
        assertThat(one).isNotNull();
        assertThat(one.get("display_name")).isEqualTo("趋势打分");
        assertThat(one.get("factor_version")).isEqualTo(StrategyCatalog.FV_TREND);
        assertThat(one.get("min_bars")).isEqualTo(60);
        assertThat(one.get("missing_data_policy")).isEqualTo("RE_NORMALIZE_OVER_AVAILABLE");
    }

    @Test
    void catalogListingIsReadOnlyAndSideEffectFreeAcrossCalls() {
        // 反复调用不得互相污染（返回的是新构建的 Map）
        List<Map<String, Object>> first = service.listStrategies();
        List<Map<String, Object>> second = service.listStrategies();

        assertThat(first).isNotSameAs(second);
        first.get(0).put("code", "TAMPERED");
        assertThat(second.get(0).get("code")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(service.listStrategies().get(0).get("code")).isEqualTo("CLASSIC_SELECTOR");
    }
}
