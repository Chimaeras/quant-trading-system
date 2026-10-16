package com.quant.service.scoring.factor;

import com.quant.entity.SelectorResultItem;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LegacyResultRowWriter} 的逐列契约测试。
 *
 * <p>这个类的存在理由：它是"统一引擎的输出能写进旧落库结构"这一主张的**执行点**。
 * 一旦某列的名称或"缺失语义"被改坏，前端 5 个 tab 会静默显示错误的数（例如
 * 「未计算」显示成 0），而不会抛任何异常——静默错误正是本类要拦住的东西。
 */
class LegacyResultRowWriterTest {

    private final LegacyResultRowWriter writer = new LegacyResultRowWriter();

    private static Map<String, Object> fullItem() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stock_code", "600000");
        m.put("exchange_type", "SSE");
        m.put("stock_name", "浦发银行");
        m.put("final_score", 87.5);
        m.put("trend_score", 12.25);
        m.put("momentum_score", 4.5);
        m.put("volume_score", 3.0);
        m.put("risk_penalty", -1.5);
        m.put("ret20", 4.17536534446763);
        m.put("ret60", 13.667425968109349);
        m.put("volatility20", 0.0024171260319901054);
        m.put("max_drawdown60", 0.0);
        m.put("avg_amount20", 2.4475E7);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("unified_engine", true);
        extra.put("factor_contributions", java.util.List.of(Map.of("factor", "TREND_MA")));
        m.put("extra_metrics", extra);
        return m;
    }

    @Test
    void smallVolatilityIsNotRoundedIntoThePlaceholderZero() {
        // 这是实测发现的缺陷：volatility20 = 0.002417 在上一个版本里被 2 位小数取整压成 0.0，
        // 于是一个**真实值**变成了与"未计算"完全相同的 0——A-3 要消灭的正是这种不可区分。
        SelectorResultItem row = writer.toRow(fullItem(), "R1", 1, "CLASSIC_SELECTOR");

        assertThat(row.getVolatility20()).isNotNull();
        assertThat(row.getVolatility20()).isNotEqualTo(0.0);
        assertThat(row.getVolatility20()).isEqualTo(0.0024171260319901054);
    }

    @Test
    void rawMetricsKeepFullPrecisionWhileScoresAreRounded() {
        SelectorResultItem row = writer.toRow(fullItem(), "R1", 1, "CLASSIC_SELECTOR");

        // 原始行情度量保留完整精度
        assertThat(row.getRet20()).isEqualTo(4.17536534446763);
        assertThat(row.getRet60()).isEqualTo(13.667425968109349);
        assertThat(row.getAvgAmount20()).isEqualTo(2.4475E7);
        // 0-100 量纲的分数保留 2 位小数即可
        assertThat(row.getFinalScore()).isEqualTo(87.5);
        assertThat(row.getTrendScore()).isEqualTo(12.25);
        assertThat(row.getRiskPenalty()).isEqualTo(-1.5);
    }

    @Test
    void realZeroIsPreservedAsZeroRatherThanBecomingNull() {
        // max_drawdown60=0 在单调上涨序列上是**真实值**，绝不能变成 NULL。
        // 这与"缺失→NULL"是相反方向的要求，必须同时成立。
        SelectorResultItem row = writer.toRow(fullItem(), "R1", 1, "CLASSIC_SELECTOR");

        assertThat(row.getMaxDrawdown60()).isNotNull();
        assertThat(row.getMaxDrawdown60()).isEqualTo(0.0);
    }

    @Test
    void missingNullableColumnsBecomeNullAndMissingScoreColumnsBecomeZero() {
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("stock_code", "000001");
        sparse.put("exchange_type", "SZSE");
        sparse.put("stock_name", "平安银行");
        sparse.put("final_score", 50.0);
        // 刻意不给 ret20/ret60/volatility20/max_drawdown60/avg_amount20

        SelectorResultItem row = writer.toRow(sparse, "R2", 3, "TREND");

        // 可空列：缺失 → NULL（与"真实 0"可区分）
        assertThat(row.getRet20()).isNull();
        assertThat(row.getRet60()).isNull();
        assertThat(row.getVolatility20()).isNull();
        assertThat(row.getMaxDrawdown60()).isNull();
        assertThat(row.getAvgAmount20()).isNull();
        // NOT NULL 列：缺失 → 0.0（库约束要求，无法为 NULL）
        assertThat(row.getTrendScore()).isEqualTo(0.0);
        assertThat(row.getMomentumScore()).isEqualTo(0.0);
        assertThat(row.getVolumeScore()).isEqualTo(0.0);
        assertThat(row.getRiskPenalty()).isEqualTo(0.0);
    }

    @Test
    void nonFiniteValuesAreTreatedAsMissing() {
        Map<String, Object> dirty = fullItem();
        dirty.put("volatility20", Double.NaN);
        dirty.put("ret20", Double.POSITIVE_INFINITY);
        dirty.put("ret60", "not-a-number");

        SelectorResultItem row = writer.toRow(dirty, "R3", 1, "TREND");

        assertThat(row.getVolatility20()).isNull();
        assertThat(row.getRet20()).isNull();
        assertThat(row.getRet60()).isNull();
    }

    @Test
    void legacyStrategyTypeAndIdentityColumnsAreCarriedThrough() {
        SelectorResultItem row = writer.toRow(fullItem(), "RESULT_X", 7, "OVERSOLD_REBOUND");

        assertThat(row.getResultId()).isEqualTo("RESULT_X");
        assertThat(row.getRankNo()).isEqualTo(7);
        assertThat(row.getStrategyType()).isEqualTo("OVERSOLD_REBOUND");
        assertThat(row.getStockCode()).isEqualTo("600000");
        assertThat(row.getExchangeType()).isEqualTo("SSE");
        assertThat(row.getStockName()).isEqualTo("浦发银行");
    }

    @Test
    void unifiedDisclosureSurvivesIntoExtraMetrics() {
        SelectorResultItem row = writer.toRow(fullItem(), "R1", 1, "CLASSIC_SELECTOR");

        // 落库不得丢掉可解释性：否则前端只能看到分数，看不到"为什么是这个分"
        assertThat(row.getExtraMetrics()).containsKey("factor_contributions");
        assertThat(row.getExtraMetrics()).containsKey("unified_engine");
        assertThat(row.getReasons()).containsKey("raw_item");
        assertThat(row.getReasons().get("unified")).isEqualTo(true);
    }

    @Test
    void alternateScoreKeyNamesAreAccepted() {
        Map<String, Object> alt = new LinkedHashMap<>();
        alt.put("code", "600519");
        alt.put("exchange", "SSE");
        alt.put("name", "贵州茅台");
        alt.put("total_score", 91.0);
        alt.put("score_trend", 5.0);
        alt.put("score_bias", 6.0);
        alt.put("score_volume", 7.0);

        SelectorResultItem row = writer.toRow(alt, "R4", 1, "TREND");

        assertThat(row.getStockCode()).isEqualTo("600519");
        assertThat(row.getFinalScore()).isEqualTo(91.0);
        assertThat(row.getTrendScore()).isEqualTo(5.0);
        assertThat(row.getMomentumScore()).isEqualTo(6.0);
        assertThat(row.getVolumeScore()).isEqualTo(7.0);
    }
}
