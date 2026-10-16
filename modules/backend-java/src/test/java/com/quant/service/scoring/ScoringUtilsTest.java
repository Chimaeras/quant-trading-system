package com.quant.service.scoring;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringUtilsTest {

    @Test
    void explicitZeroVolumeOnlyWhenVolumeIsZeroNotNull() {
        MarketData zero = new MarketData();
        zero.setVolume(0L);
        MarketData missing = new MarketData();
        MarketData trading = new MarketData();
        trading.setVolume(1000L);

        assertThat(ScoringUtils.isExplicitZeroVolume(zero)).isTrue();
        assertThat(ScoringUtils.isExplicitZeroVolume(missing)).isFalse();
        assertThat(ScoringUtils.isExplicitZeroVolume(trading)).isFalse();
    }

    @Test
    void isSignalBarOnTradeDateRejectsStaleLastBar() {
        MarketData stale = new MarketData();
        stale.setTradeDate(LocalDate.of(2024, 3, 1));
        stale.setClosePrice(java.math.BigDecimal.TEN);
        assertThat(ScoringUtils.isSignalBarOnTradeDate(List.of(stale), LocalDate.of(2024, 6, 1)))
                .isFalse();
        assertThat(ScoringUtils.isSignalBarOnTradeDate(List.of(stale), LocalDate.of(2024, 3, 1)))
                .isTrue();
        assertThat(ScoringUtils.isSignalBarOnTradeDate(List.of(stale), null)).isTrue();
    }

    @Test
    void tradableForScoringRejectsZeroVolumeAndStWhenEnabled() {
        MarketData zeroVol = new MarketData();
        zeroVol.setVolume(0L);
        MarketData normal = new MarketData();
        normal.setVolume(1000L);

        assertThat(ScoringUtils.isTradableForScoring(zeroVol, "测试股份", true)).isFalse();
        assertThat(ScoringUtils.isTradableForScoring(normal, "*ST测试", true)).isFalse();
        assertThat(ScoringUtils.isTradableForScoring(normal, "*ST测试", false)).isTrue();
        assertThat(ScoringUtils.isTradableForScoring(normal, "测试股份", true)).isTrue();
    }

    @Test
    void tradableForScoringRejectsChiNext301LimitUpAtTwentyPercent() {
        MarketData prev = bar("301578", "SZSE", 10.0);
        MarketData limitUp20 = bar("301578", "SZSE", 12.0);
        MarketData notLimitUp = bar("301578", "SZSE", 11.0);

        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitUp20), "301578", "SZSE", "注册制创业板", true, true, true))
                .isFalse();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, notLimitUp), "301578", "SZSE", "注册制创业板", true, true, true))
                .isTrue();
    }

    @Test
    void tradableForScoringRejectsLimitUpAtCloseAndHonorsOversoldLimitDownException() {
        MarketData prev = bar("000001", "SZSE", 10.0);
        MarketData limitUpClose = bar("000001", "SZSE", 11.0);
        MarketData limitDownClose = bar("000001", "SZSE", 9.0);
        MarketData normalClose = bar("000001", "SZSE", 10.5);

        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitUpClose), "000001", "SZSE", "测试股份", true, true, true))
                .isFalse();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitDownClose), "000001", "SZSE", "测试股份", true, true, true))
                .isFalse();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitDownClose), "000001", "SZSE", "测试股份", true, true, false))
                .isTrue();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, normalClose), "000001", "SZSE", "测试股份", true, true, true))
                .isTrue();
    }

    // ── V30 / P0-1 回归测试：两处「静默放行」漏洞 ──────────────────────────────
    // 旧实现在这两种情形下都无痕放行，已实测导致 ST 股进入候选池（CLASSIC_SELECTOR 3 行、TREND 4 行）。

    @Test
    void strictTradabilityRejectsBlankStockNameInsteadOfBypassingStGate() {
        MarketData normal = new MarketData();
        normal.setVolume(1000L);
        MarketData prev = bar("000001", "SZSE", 10.0);
        MarketData signal = bar("000001", "SZSE", 10.5);

        // 旧行为：stockName 为 null/blank 时 `stockName != null && ...` 短路 → ST 闸整体失效。
        // 新行为：名称缺失是数据缺陷，严格模式下拒绝。
        // 注意：stockName 传 null 字面量会与 (MarketData signalBar, ...) 重载产生歧义（进而绑错参数），
        // 故此处显式声明为 (String) null。
        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, signal), "000001", "SZSE", (String) null, 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.MISSING_STOCK_NAME);
        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, signal), "000001", "SZSE", "   ", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.MISSING_STOCK_NAME);
        // 名称缺失的拒绝**不区分严格/非严格**：生产路径上 5 套打分都会把缺失名称兜底成 ""
        // 或股票代码，空名称本就不该出现；一旦出现宁可拒绝，也不放 ST 进来。
        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, signal), "000001", "SZSE", (String) null, 1, true, true, true, false))
                .isEqualTo(TradabilityCheck.MISSING_STOCK_NAME);
        // 名称正常时不受影响
        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, signal), "000001", "SZSE", "测试股份", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.TRADABLE);
        // ST 名称依然被拒
        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, signal), "000001", "SZSE", "*ST测试", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.ST_NAME);
    }

    @Test
    void strictTradabilityRejectsMissingPreCloseInsteadOfBypassingBothLimitGates() {
        MarketData prev = bar("000001", "SZSE", 10.0);
        MarketData limitUp = bar("000001", "SZSE", 11.0);

        // 只有 1 根 K 线 → resolvePreClose 为 null。
        // 旧行为：`if (preClose == null ...) return true;` 位于涨跌停判定之前 ⟹ 涨停闸与跌停闸同时失效。
        assertThat(ScoringUtils.evaluateTradability(
                List.of(limitUp), "000001", "SZSE", "测试股份", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.MISSING_PRE_CLOSE);
        // 非严格模式保留旧语义，但降级状态可被观测
        TradabilityCheck degraded = ScoringUtils.evaluateTradability(
                List.of(limitUp), "000001", "SZSE", "测试股份", 1, true, true, true, false);
        assertThat(degraded).isEqualTo(TradabilityCheck.MISSING_PRE_CLOSE_DEGRADED);
        assertThat(degraded.isTradable()).isTrue();
        assertThat(degraded.isDegraded()).isTrue();
        // 有 preClose 时涨停闸正常生效
        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, limitUp), "000001", "SZSE", "测试股份", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.LIMIT_UP_AT_CLOSE);
    }

    @Test
    void strictTradabilityRejectsMissingStockCode() {
        MarketData prev = bar("000001", "SZSE", 10.0);
        MarketData signal = bar("000001", "SZSE", 10.5);

        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, signal), null, "SZSE", "测试股份", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.MISSING_STOCK_CODE);
        assertThat(ScoringUtils.evaluateTradability(
                List.of(prev, signal), "", "SZSE", "测试股份", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.MISSING_STOCK_CODE);
    }

    @Test
    void tradabilityCheckReasonCodesAreStableAndClassified() {
        assertThat(TradabilityCheck.TRADABLE.isTradable()).isTrue();
        assertThat(TradabilityCheck.TRADABLE.isDegraded()).isFalse();
        assertThat(TradabilityCheck.ST_NAME.isTradable()).isFalse();
        assertThat(TradabilityCheck.INACTIVE_STATUS.isTradable()).isFalse();
        assertThat(TradabilityCheck.NO_BARS.isTradable()).isFalse();
        assertThat(TradabilityCheck.ZERO_VOLUME.isTradable()).isFalse();
        assertThat(TradabilityCheck.LIMIT_UP_AT_CLOSE.isTradable()).isFalse();
        assertThat(TradabilityCheck.LIMIT_DOWN_AT_CLOSE.isTradable()).isFalse();
        assertThat(TradabilityCheck.MISSING_STOCK_NAME.isTradable()).isFalse();
        assertThat(TradabilityCheck.MISSING_PRE_CLOSE.isTradable()).isFalse();
        assertThat(TradabilityCheck.MISSING_STOCK_CODE.isTradable()).isFalse();
        assertThat(TradabilityCheck.MISSING_PRE_CLOSE_DEGRADED.isDegraded()).isTrue();
        assertThat(TradabilityCheck.TRADABLE.code()).isEqualTo("TRADABLE");
    }

    private static MarketData bar(String code, String exchange, double close) {        MarketData signal = new MarketData();
        signal.setStockCode(code);
        signal.setExchangeType(exchange);
        signal.setClosePrice(java.math.BigDecimal.valueOf(close));
        signal.setVolume(1000L);
        return signal;
    }

    @Test
    void hasMinListingTenureHonorsThresholdAndMissingListDate() {
        LocalDate trade = LocalDate.of(2026, 6, 30);
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60)).isTrue();
        assertThat(ScoringUtils.hasMinListingTenure(trade.minusDays(30), trade, 60)).isFalse();
        assertThat(ScoringUtils.hasMinListingTenure(trade.minusDays(90), trade, 60)).isTrue();
        assertThat(ScoringUtils.hasMinListingTenure(trade.plusDays(1), trade, 60)).isFalse();
    }

    @Test
    void tradabilitySnapshotReflectsOversoldLimitDownException() {
        Map<String, Object> normal = ScoringUtils.tradabilitySnapshot(true, true, 60);
        assertThat(normal.get("exclude_limit_down_at_close")).isEqualTo(true);
        assertThat(normal.get("min_listing_days")).isEqualTo(60);
        assertThat(normal.get("avg_amount20_basis"))
                .isEqualTo("MEAN_AMOUNT_LAST_20_NON_ZERO_VOLUME_DAYS");

        Map<String, Object> oversold = ScoringUtils.tradabilitySnapshot(true, false, 60);
        assertThat(oversold.get("exclude_limit_down_at_close")).isEqualTo(false);
    }

    @Test
    void tradabilitySnapshotDisclosesLookbackScopeWhenBulkFetchDisabled() {
        Map<String, Object> bulk = ScoringUtils.tradabilitySnapshot(true, true, 60, true);
        Map<String, Object> window = ScoringUtils.tradabilitySnapshot(true, true, 60, false);

        assertThat(bulk.get("listing_tenure_bar_scope")).isEqualTo("FULL_BULK_BARS_IN_LOOKBACK");
        assertThat(window.get("listing_tenure_bar_scope")).isEqualTo("LOOKBACK_WINDOW_EARLIEST_TRADE_DATE_ONLY");
        assertThat(window.get("listing_tenure_warning")).isNotNull();
        assertThat(window.get("bulk_fetch")).isEqualTo(false);
    }

    @Test
    void pickTopNByMinScoreAllowsFewerThanTopNWhenQualityGateFails() {
        List<Map<String, Object>> items = List.of(
                Map.of("stock_code", "A", "total_score", 80),
                Map.of("stock_code", "B", "total_score", 40),
                Map.of("stock_code", "C", "total_score", 60)
        );

        var out = ScoringUtils.pickTopNByMinScore(items, 55, 10, "total_score");

        assertThat(out).hasSize(2);
        assertThat(out.get(0).get("stock_code")).isEqualTo("A");
        assertThat(out.get(1).get("stock_code")).isEqualTo("C");
        assertThat(ScoringUtils.countByMinScore(items, 55, "total_score")).isEqualTo(2);
    }

    @Test
    void avgAmount20ExcludesZeroVolumeDaysFromLastTwentyTraded() {
        List<MarketData> bars = sampleAmountBars(25, 50_000_000);
        for (int i = 20; i < 25; i++) {
            bars.get(i).setVolume(0L);
            bars.get(i).setAmount(java.math.BigDecimal.ZERO);
        }

        assertThat(ScoringUtils.avgAmount20(bars)).isEqualTo(50_000_000.0);
        assertThat(ScoringUtils.passesMinAvgAmount(bars, 40_000_000)).isTrue();
    }

    @Test
    void passesMinAvgAmountHonorsTwentyDayAverageThreshold() {
        List<MarketData> low = sampleAmountBars(25, 500_000);
        List<MarketData> high = sampleAmountBars(25, 50_000_000);

        assertThat(ScoringUtils.passesMinAvgAmount(low, 1_000_000)).isFalse();
        assertThat(ScoringUtils.passesMinAvgAmount(high, 1_000_000)).isTrue();
        assertThat(ScoringUtils.passesMinAvgAmount(low, 0)).isTrue();
    }

    @Test
    void avgAmount20AveragesOverAvailableDaysWhenFewerThanTwenty() {
        // 仅 5 个有效成交日：按实际天数求平均，不直接归零
        List<MarketData> shortSeries = sampleAmountBars(5, 80_000_000);
        assertThat(ScoringUtils.avgAmount20(shortSeries)).isEqualTo(80_000_000.0);
        assertThat(ScoringUtils.passesMinAvgAmount(shortSeries, 60_000_000)).isTrue();

        // 零有效成交日 → 0
        List<MarketData> allZero = sampleAmountBars(10, 80_000_000);
        for (MarketData bar : allZero) {
            bar.setVolume(0L);
        }
        assertThat(ScoringUtils.avgAmount20(allZero)).isZero();
    }

    @Test
    void isActiveStockStatusAllowsNormalOrMissingOnly() {
        assertThat(ScoringUtils.isActiveStockStatus(1)).isTrue();
        assertThat(ScoringUtils.isActiveStockStatus(null)).isTrue();
        assertThat(ScoringUtils.isActiveStockStatus(0)).isFalse();
        assertThat(ScoringUtils.isActiveStockStatus(-1)).isFalse();
    }

    @Test
    void listingTenureBarsFallsBackWhenBulkBarsEmpty() {
        LocalDate earliest = LocalDate.of(2024, 1, 1);
        List<MarketData> out = ScoringUtils.listingTenureBars(List.of(), earliest);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTradeDate()).isEqualTo(earliest);
    }

    @Test
    void listingTenureBarsUsesEarliestWhenBulkBarsMissing() {
        LocalDate trade = LocalDate.of(2024, 6, 1);
        LocalDate earliest = trade.minusDays(90);
        List<MarketData> proxy = ScoringUtils.listingTenureBars(null, earliest);
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, proxy)).isTrue();
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, null)).isTrue();
    }

    @Test
    void mapEarliestTradeDatesByKeyBuildsStockKeyIndex() {
        List<Object[]> rows = List.of(
                new Object[]{"000001", "SZSE", LocalDate.of(2010, 1, 1)},
                new Object[]{"000002", "SZSE", java.sql.Date.valueOf(LocalDate.of(2012, 3, 1))}
        );
        Map<String, LocalDate> map = ScoringUtils.mapEarliestTradeDatesByKey(rows);
        assertThat(map.get("000001|SZSE")).isEqualTo(LocalDate.of(2010, 1, 1));
        assertThat(map.get("000002|SZSE")).isEqualTo(LocalDate.of(2012, 3, 1));
    }

    @Test
    void hasMinListingTenureUsesEarliestBarWhenListDateMissing() {
        LocalDate trade = LocalDate.of(2024, 6, 1);
        List<MarketData> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            MarketData bar = new MarketData();
            bar.setTradeDate(trade.minusDays(79 - i));
            bars.add(bar);
        }
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, bars)).isTrue();
        List<MarketData> shortHistory = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            MarketData bar = new MarketData();
            bar.setTradeDate(trade.minusDays(30 - i));
            shortHistory.add(bar);
        }
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, shortHistory)).isFalse();
    }

    @Test
    void tradableForScoringRejectsSuspendedStatus() {
        MarketData normal = new MarketData();
        normal.setVolume(1000L);
        normal.setClosePrice(java.math.BigDecimal.TEN);
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(normal), "000001", "SZSE", "测试股份", 0, true, true, true)).isFalse();
        // V30 修正：旧断言此处为 isTrue()，把「单根 K 线 → preClose 为 null → 涨跌停闸双双失效」
        // 这一数据缺陷当成了正确行为。严格模式下应拒绝。
        assertThat(ScoringUtils.evaluateTradability(
                List.of(normal), "000001", "SZSE", "测试股份", 1, true, true, true, true))
                .isEqualTo(TradabilityCheck.MISSING_PRE_CLOSE);
        // 非严格模式（旧 8 参布尔重载）保留放行语义，但状态可观测为降级
        assertThat(ScoringUtils.evaluateTradability(
                List.of(normal), "000001", "SZSE", "测试股份", 1, true, true, true, false))
                .isEqualTo(TradabilityCheck.MISSING_PRE_CLOSE_DEGRADED);
        // 有前一根 K 线、preClose 可解析时，活跃状态正常通过
        MarketData prev = bar("000001", "SZSE", 10.0);
        MarketData signal = bar("000001", "SZSE", 10.5);
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, signal), "000001", "SZSE", "测试股份", 1, true, true, true)).isTrue();
    }

    private static List<MarketData> sampleAmountBars(int count, double amount) {
        List<MarketData> bars = new java.util.ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < count; i++) {
            MarketData bar = new MarketData();
            bar.setTradeDate(start.plusDays(i));
            bar.setAmount(java.math.BigDecimal.valueOf(amount));
            bar.setVolume(1000L);
            bars.add(bar);
        }
        return bars;
    }
}
