package com.quant.service.scoring;

import com.quant.entity.SelectorResultItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V30 / P0-3 回归测试：「未计算」必须以 NULL 表达，不得写成占位 0。
 *
 * <p>背景（实测证据，见 V25 与 V30 迁移）：
 * <ul>
 *   <li>{@code selector_result_item} 的 {@code ret20 / ret60 / volatility20 / max_drawdown60}
 *       在 TREND / MULTI_DIM / COMPREHENSIVE 三套策略上<b>整列 100% 为 0</b>（TREND 2550 行、
 *       MULTI_DIM 153 行、COMPREHENSIVE 30 行），而底层 market_data 数据齐全——
 *       即那是「未计算」的占位 0，会被研究侧当特征读走。</li>
 *   <li>反向陷阱：{@code ret20=0} 中有 <b>4 行是真实值</b>（个股恰好回到 20 个交易日前的价格，
 *       复算 = 0.0000%），故不能简单地把所有 0 都当占位。</li>
 * </ul>
 *
 * <p>本测试锁定写入侧行为：{@code null} 进则 {@code null} 出；只有 NOT NULL 列才退化为 0。
 */
class ProgressiveResultWriterSanitizeTest {

    /** 借反射调用私有的 sanitizeNumericFields——它是「null → 0」的唯一发生点。 */
    private static void sanitize(SelectorResultItem item) throws Exception {
        Method m = ProgressiveResultWriter.class
                .getDeclaredMethod("sanitizeNumericFields", SelectorResultItem.class);
        m.setAccessible(true);
        m.invoke(null, item);
    }

    @Test
    void nullUncomputedColumnsStayNullInsteadOfBecomingZero() throws Exception {
        SelectorResultItem item = new SelectorResultItem();
        // 模拟 TREND / MULTI_DIM / COMPREHENSIVE 的写入方：这几块特征根本没算，值为 null
        item.setFinalScore(67.17);
        item.setTrendScore(60.0);
        item.setMomentumScore(0.0);
        item.setVolumeScore(0.0);
        item.setRiskPenalty(0.0);
        item.setRet20(null);
        item.setRet60(null);
        item.setVolatility20(null);
        item.setMaxDrawdown60(null);
        item.setAvgAmount20(null);

        sanitize(item);

        assertThat(item.getRet20()).isNull();
        assertThat(item.getRet60()).isNull();
        assertThat(item.getVolatility20()).isNull();
        assertThat(item.getMaxDrawdown60()).isNull();
        assertThat(item.getAvgAmount20()).isNull();
    }

    @Test
    void realZeroValuesArePreservedNotTurnedIntoNull() throws Exception {
        SelectorResultItem item = new SelectorResultItem();
        // 4 行真实 ret20=0（个股恰好回到 20 日前价格）必须原样保留
        item.setRet20(0.0);
        item.setRet60(0.0);
        item.setVolatility20(0.0);
        item.setMaxDrawdown60(0.0);
        item.setAvgAmount20(0.0);

        sanitize(item);

        assertThat(item.getRet20()).isZero();
        assertThat(item.getRet60()).isZero();
        assertThat(item.getVolatility20()).isZero();
        assertThat(item.getMaxDrawdown60()).isZero();
        assertThat(item.getAvgAmount20()).isZero();
    }

    @Test
    void notNullColumnsStillDegradeToZeroBecauseColumnRejectsNull() throws Exception {
        SelectorResultItem item = new SelectorResultItem();
        item.setFinalScore(null);
        item.setTrendScore(null);
        item.setMomentumScore(null);
        item.setVolumeScore(null);
        item.setRiskPenalty(null);

        sanitize(item);

        // 这五列在 DDL 中仍是 NOT NULL（V2），无法表达「不可用」，只能退化为 0
        assertThat(item.getFinalScore()).isZero();
        assertThat(item.getTrendScore()).isZero();
        assertThat(item.getMomentumScore()).isZero();
        assertThat(item.getVolumeScore()).isZero();
        assertThat(item.getRiskPenalty()).isZero();
    }

    @Test
    void nonFiniteValuesBecomeNullForNullableColumnsAndZeroForNotNullColumns() throws Exception {
        SelectorResultItem item = new SelectorResultItem();
        item.setRet20(Double.NaN);
        item.setVolatility20(Double.POSITIVE_INFINITY);
        item.setMaxDrawdown60(Double.NEGATIVE_INFINITY);
        item.setFinalScore(Double.NaN);

        sanitize(item);

        assertThat(item.getRet20()).isNull();
        assertThat(item.getVolatility20()).isNull();
        assertThat(item.getMaxDrawdown60()).isNull();
        assertThat(item.getFinalScore()).isZero();
    }
}
