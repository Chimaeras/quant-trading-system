package com.quant.service.scoring.factor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L3 层测试：{@link StrategyCatalog} 与 {@link StrategyDefinition}。
 *
 * <p>核心断言：权重必须归一化（消除当前「MULTI_DIM 预归一化权重和 1.10」这类不一致），
 * 且 5 个策略都有唯一的 {@code factorVersion}（消除当前 CLASSIC_SELECTOR 一个策略
 * 对应 3 个不同 factor_version 字符串的漂移）。
 */
class StrategyCatalogTest {

    @Test
    void allFiveStrategiesAreDeclared() {
        List<StrategyDefinition> defs = StrategyCatalog.all();
        assertThat(defs).hasSize(5);
        assertThat(defs).extracting(StrategyDefinition::legacyStrategyType)
                .containsExactlyInAnyOrder("CLASSIC_SELECTOR", "TREND", "OVERSOLD_REBOUND",
                        "COMPREHENSIVE", "MULTI_DIM");
        assertThat(defs).extracting(StrategyDefinition::displayName)
                .containsExactly("经典多因子", "趋势打分", "超跌反弹", "综合三维度", "多维度v1");
    }

    @Test
    void everyStrategyHasUniqueFactorVersion() {
        List<String> versions = StrategyCatalog.all().stream()
                .map(StrategyDefinition::factorVersion).toList();
        assertThat(versions).doesNotHaveDuplicates();
        assertThat(versions).allMatch(v -> v.startsWith("unified_factor_"));
    }

    @Test
    void normalizedWeightsSumToOneInAbsoluteValueForEveryStrategy() {
        for (StrategyDefinition d : StrategyCatalog.all()) {
            double sum = d.normalizedWeights().values().stream()
                    .mapToDouble(Math::abs).sum();
            assertThat(sum)
                    .as("策略 %s 的归一化权重绝对值之和", d.code())
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void everyStrategyDeclaresAtLeastOneFactorAndEveryFactorIsKnown() {
        for (StrategyDefinition d : StrategyCatalog.all()) {
            assertThat(d.weights()).as("策略 %s 的因子", d.code()).isNotEmpty();
            assertThat(d.weights().keySet()).allMatch(java.util.Objects::nonNull);
            assertThat(d.minBars()).isPositive();
            assertThat(d.minAvailableWeightRatio()).isBetween(0d, 1d);
        }
    }

    @Test
    void lookupsByLegacyTypeAndAliasesWork() {
        assertThat(StrategyCatalog.byLegacyStrategyType("TREND").code()).isEqualTo("TREND");
        assertThat(StrategyCatalog.byLegacyStrategyType("trend").code()).isEqualTo("TREND");
        assertThat(StrategyCatalog.byLegacyStrategyType("CLASSIC").code())
                .isEqualTo("CLASSIC_SELECTOR");
        assertThat(StrategyCatalog.byLegacyStrategyType("MULTI-DIM").code())
                .isEqualTo("MULTI_DIM");
        assertThat(StrategyCatalog.byLegacyStrategyType("OVERSOLD").code())
                .isEqualTo("OVERSOLD_REBOUND");
        assertThat(StrategyCatalog.byLegacyStrategyType("GEOPOLICY_NEWS")).isNull();
        assertThat(StrategyCatalog.byLegacyStrategyType(null)).isNull();
    }

    @Test
    void oversoldStrategyRequiresVolumePriceConfirmation() {
        // 原实现的"确认分"是必需项（无确认时加权分封顶 70），本目录用 requiredFactors 表达
        StrategyDefinition oversold = StrategyCatalog.oversold();
        assertThat(oversold.requiredFactors()).containsExactly(FactorId.VOL_PRICE);
    }

    @Test
    void riskFactorsCarryNegativeWeights() {
        for (StrategyDefinition d : StrategyCatalog.all()) {
            if (d.weights().containsKey(FactorId.VOLATILITY_20)) {
                assertThat(d.weights().get(FactorId.VOLATILITY_20))
                        .as("策略 %s 的波动率权重应为负", d.code()).isNegative();
            }
        }
    }

    @Test
    void comprehensiveAndMultiDimExposeHowMuchWeightDependsOnCurrentlyMissingFundamentals() {
        // 本库 PE/PB 全缺、financial_snapshot 0 行 ⟹ 基本面权重必然是「结构性不可用」的那部分。
        // 该比例必须可查询，供输出披露。
        StrategyDefinition comp = StrategyCatalog.comprehensive();
        assertThat(comp.nonFundamentalWeightShare()).isBetween(0d, 1d);
        assertThat(comp.nonFundamentalWeightShare()).isLessThan(1.0);

        StrategyDefinition multi = StrategyCatalog.multiDim();
        assertThat(multi.nonFundamentalWeightShare()).isGreaterThan(0d);
    }

    @Test
    void builderRejectsStrategyWithoutAnyFactor() {
        assertThatThrownBy(() -> StrategyDefinition
                .builder("X", "空策略", "v1").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明任何因子权重");
    }

    @Test
    void minBarsAreNoLongerTheInconsistentThirtySixtySeventyTwoSixtySet() {
        // 原实现服务级 MIN_BARS 为 30/60/70/260 四个值；
        // 统一层把「策略级最小 bar」明确为各自的原值（MULTI_DIM 由 30 提到 260，
        // 因为 30 根会让多数技术因子不可用却仍参与加权，正是 stdev=0.993 的成因之一）。
        assertThat(StrategyCatalog.classic().minBars()).isEqualTo(70);
        assertThat(StrategyCatalog.trend().minBars()).isEqualTo(60);
        assertThat(StrategyCatalog.comprehensive().minBars()).isEqualTo(60);
        assertThat(StrategyCatalog.oversold().minBars()).isEqualTo(260);
        assertThat(StrategyCatalog.multiDim().minBars()).isEqualTo(260);
    }
}
