package com.quant.service;

import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.TradingCalendarDayRepository;
import com.quant.repository.IndexQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量驾驶舱：缺失交易日、list_date 缺失、更新滞后告警、复权口径披露。
 *
 * <p>口径：
 * <ul>
 *   <li>缺失交易日：用 market_data 的 distinct trade_date 与「自然日排除周末」的预期交易日比对；
 *       该预期未剔除法定节假日，长假前后略有偏差（已在前端提示）。</li>
 *   <li>更新滞后：最新交易日距今天数 > 阈值时告警（阈值默认 3 天，覆盖周末）。</li>
 *   <li>复权口径：当前系统以前复权为准（与 HistoricalDataService 口径一致），此处仅披露。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityService {

    private static final long STALE_DAYS_THRESHOLD = 3L;

    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final AdjustFactorRepository adjustFactorRepository;
    private final SecurityMasterHistoryRepository securityMasterHistoryRepository;
    private final TradingCalendarDayRepository tradingCalendarDayRepository;
    private final IndexQuoteRepository indexQuoteRepository;
    private final StockInfoRepository stockInfoRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> buildQualityReport() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 1. 交易日范围与缺失
        LocalDate minDate = marketDataRepository.findMinTradeDate().orElse(null);
        LocalDate maxDate = marketDataRepository.findMaxTradeDate().orElse(null);
        out.put("earliest_date", minDate != null ? minDate.toString() : "");
        out.put("latest_date", maxDate != null ? maxDate.toString() : "");

        Map<String, Object> missingDays = buildMissingTradingDays(minDate, maxDate);
        out.put("trading_days", missingDays);

        // 2. 更新滞后告警
        Map<String, Object> staleness = buildStaleness(maxDate);
        out.put("update_staleness", staleness);

        // 3. list_date 缺失
        long listDateMissing = stockInfoRepository.countByListDateIsNull();
        Map<String, Object> listDate = new LinkedHashMap<>();
        listDate.put("missing_count", listDateMissing);
        listDate.put("backfillable_count", listDateMissing > 0
                ? marketDataRepository.countBackfillableListDateCandidates() : 0L);
        listDate.put("note", "list_date 缺失时可用本地 K 线最早交易日回填，非交易所官方上市日");
        out.put("list_date", listDate);

        // 4. 复权口径披露
        Map<String, Object> adjust = new LinkedHashMap<>();
        adjust.put("current_basis", "前复权（qfq）");
        adjust.put("scope", "K 线与技术指标计算均使用前复权；模拟成交价以前复权收盘/开盘为准");
        adjust.put("warning", "前复权会因新增除权事件向后修正历史价格，回测结果可能随数据更新而变化");
        out.put("adjust_factor", adjust);

        // 5. QP-003：不复权行情与复权因子覆盖（PIT 数据底座进度披露）
        out.put("raw_and_adjust", buildRawAndAdjustCoverage());

        // 6. QP-004：历史证券主数据覆盖（PIT ST/退市/行业变更消除进度）
        out.put("security_master", buildSecurityMasterCoverage());

        // 7. QP-005：真实交易日历覆盖与差异（替代自然日排除周末近似）
        out.put("trading_calendar", buildTradingCalendarCoverage(minDate, maxDate));

        // 8. QP-005：指数数据覆盖（沪深300/中证500/中证1000/上证指数）
        out.put("index_data", buildIndexCoverage());

        // 9. 整体健康度
        out.put("health", buildHealth(missingDays, staleness, listDateMissing));
        return out;
    }

    /**
     * QP-003c：不复权行情（market_price_raw）与复权因子（adjust_factor）覆盖统计，
     * 用于数据质量页展示 PIT 数据底座建设进度与 source/reliability 审计。
     */
    private Map<String, Object> buildRawAndAdjustCoverage() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 不复权行情覆盖
        Map<String, Object> raw = new LinkedHashMap<>();
        long rawRows = marketPriceRawRepository.countAll();
        raw.put("total_rows", rawRows);
        raw.put("distinct_stocks", marketPriceRawRepository.countDistinctStocks());
        raw.put("distinct_trade_dates", marketPriceRawRepository.countDistinctTradeDates());
        marketPriceRawRepository.findMinTradeDate()
                .ifPresentOrElse(d -> raw.put("earliest_date", d.toString()), () -> raw.put("earliest_date", ""));
        marketPriceRawRepository.findMaxTradeDate()
                .ifPresentOrElse(d -> raw.put("latest_date", d.toString()), () -> raw.put("latest_date", ""));
        raw.put("source", "东方财富 fqt=0 不复权");
        raw.put("status", rawRows > 0 ? "已启用" : "未回填（可通过 /api/data/backfill-raw 回填）");
        out.put("market_price_raw", raw);

        // 复权因子覆盖
        Map<String, Object> factor = new LinkedHashMap<>();
        long factorRows = adjustFactorRepository.countAll();
        factor.put("total_rows", factorRows);
        factor.put("distinct_stocks", adjustFactorRepository.countDistinctStocks());
        long derivedLocal = adjustFactorRepository.countBySource("derived_local");
        long official = adjustFactorRepository.countBySource("official");
        long limited = adjustFactorRepository.countByReliability("limited");
        Map<String, Object> sourceBreakdown = new LinkedHashMap<>();
        sourceBreakdown.put("derived_local", derivedLocal);
        sourceBreakdown.put("official", official);
        factor.put("by_source", sourceBreakdown);
        Map<String, Object> reliabilityBreakdown = new LinkedHashMap<>();
        reliabilityBreakdown.put("limited", limited);
        factor.put("by_reliability", reliabilityBreakdown);
        factor.put("status", factorRows > 0 ? "已启用（本地推算，可靠度 limited）" : "未生成");
        factor.put("note", "factor = qfq_close / raw_close；本地推算可靠度受现有数据质量限制，后续接入官方源替换");
        out.put("adjust_factor", factor);

        return out;
    }

    /**
     * QP-004：历史证券主数据（security_master_history）覆盖统计，
     * 用于数据质量页展示 PIT 历史状态（ST/退市/行业变更）消除进度与 source/reliability 审计。
     */
    private Map<String, Object> buildSecurityMasterCoverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        long rows = securityMasterHistoryRepository.count();
        long distinctStocks = securityMasterHistoryRepository.countDistinctStocks();
        long currentSnapshot = securityMasterHistoryRepository.countBySource("current_snapshot");
        long eastmoney = securityMasterHistoryRepository.countBySource("eastmoney");
        long tushare = securityMasterHistoryRepository.countBySource("tushare");
        long reliabilityLimited = securityMasterHistoryRepository.countByReliability("limited");
        long reliabilityOfficial = securityMasterHistoryRepository.countByReliability("official");

        out.put("total_rows", rows);
        out.put("distinct_stocks", distinctStocks);
        Map<String, Object> sourceBreakdown = new LinkedHashMap<>();
        sourceBreakdown.put("current_snapshot", currentSnapshot);
        sourceBreakdown.put("eastmoney", eastmoney);
        sourceBreakdown.put("tushare", tushare);
        out.put("by_source", sourceBreakdown);
        Map<String, Object> reliabilityBreakdown = new LinkedHashMap<>();
        reliabilityBreakdown.put("limited", reliabilityLimited);
        reliabilityBreakdown.put("official", reliabilityOfficial);
        out.put("by_reliability", reliabilityBreakdown);
        out.put("status", rows > 0 ? "已启用（当前快照，可靠度 limited，无历史变更记录）" : "未初始化（可通过 /api/data/security-master/init 初始化）");
        out.put("note", "当前用 stock_info 快照初始化单条 valid_from=上市日/valid_to=null 记录，无历史 ST/改名/行业变更；"
                + "仅保证上市日至今状态不变标的的 PIT 查询正确，后续接入官方历史源替换");
        return out;
    }

    /**
     * QP-005：真实交易日历覆盖统计 + 与 market_data 实际交易日的差异（替代自然日排除周末近似）。
     */
    private Map<String, Object> buildTradingCalendarCoverage(LocalDate mdMin, LocalDate mdMax) {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = tradingCalendarDayRepository.count();
        out.put("total_rows", total);
        out.put("by_source", Map.of(
                "eastmoney_derived", tradingCalendarDayRepository.countBySource("eastmoney_derived"),
                "tushare", tradingCalendarDayRepository.countBySource("tushare")));
        out.put("by_reliability", Map.of(
                "limited", tradingCalendarDayRepository.countByReliability("limited"),
                "official", tradingCalendarDayRepository.countByReliability("official")));
        out.put("status", total > 0 ? "已启用（上证指数反推，可靠度 limited）" : "未初始化（可通过 /api/data/trading-calendar/init 初始化）");
        out.put("note", "由上证指数日K反推真实交易日，覆盖法定节假日与临时休市，替代自然日排除周末近似；"
                + "后续接入 Tushare trade_cal 替换为 official");
        if (total > 0) {
            tradingCalendarDayRepository.findMinTradeDate()
                    .ifPresentOrElse(d -> out.put("earliest_date", d.toString()), () -> out.put("earliest_date", ""));
            tradingCalendarDayRepository.findMaxTradeDate()
                    .ifPresentOrElse(d -> out.put("latest_date", d.toString()), () -> out.put("latest_date", ""));
        } else {
            out.put("earliest_date", "");
            out.put("latest_date", "");
        }

        // 真实交易日历 vs market_data 实际交易日差异
        Map<String, Object> diff = new LinkedHashMap<>();
        if (total == 0 || mdMin == null || mdMax == null) {
            diff.put("calendar_days", 0);
            diff.put("market_data_days", 0);
            diff.put("missing_in_market_data", 0);
            diff.put("missing_dates", List.of());
            diff.put("note", "交易日历或行情数据为空，无法对比");
        } else {
            List<LocalDate> calendarDays = tradingCalendarDayRepository.findTradeDatesBetween(mdMin, mdMax);
            List<LocalDate> mdDays = marketDataRepository.findDistinctTradeDatesBetween(mdMin, mdMax);
            java.util.Set<LocalDate> mdSet = new java.util.HashSet<>(mdDays);
            List<String> missing = calendarDays.stream()
                    .filter(d -> !mdSet.contains(d))
                    .map(LocalDate::toString)
                    .toList();
            diff.put("calendar_days", calendarDays.size());
            diff.put("market_data_days", mdDays.size());
            diff.put("missing_in_market_data", missing.size());
            diff.put("missing_dates", missing.size() > 50 ? missing.subList(0, 50) : missing);
            diff.put("note", "真实交易日历中 market_data 缺失的交易日（行情未入库）；非零提示需补拉行情");
        }
        out.put("diff_vs_market_data", diff);
        return out;
    }

    /**
     * QP-005：指数数据覆盖统计（沪深300/中证500/中证1000/上证指数）。
     */
    private Map<String, Object> buildIndexCoverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = indexQuoteRepository.count();
        out.put("total_rows", total);
        out.put("distinct_indices", indexQuoteRepository.countDistinctIndices());

        String[][] indices = IndexDataService.SUPPORTED_INDICES;
        List<Map<String, Object>> indexList = new ArrayList<>();
        for (String[] meta : indices) {
            String code = meta[1];
            String name = meta[2];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_code", code);
            m.put("index_name", name);
            long idxRows = indexQuoteRepository.countByIndex(code);
            m.put("rows", idxRows);
            if (idxRows > 0) {
                indexQuoteRepository.findMinTradeDateByIndex(code)
                        .ifPresentOrElse(d -> m.put("earliest_date", d.toString()), () -> m.put("earliest_date", ""));
                indexQuoteRepository.findMaxTradeDateByIndex(code)
                        .ifPresentOrElse(d -> m.put("latest_date", d.toString()), () -> m.put("latest_date", ""));
            } else {
                m.put("earliest_date", "");
                m.put("latest_date", "");
            }
            indexList.add(m);
        }
        out.put("indices", indexList);
        out.put("by_source", Map.of(
                "eastmoney", indexQuoteRepository.countBySource("eastmoney"),
                "tushare", indexQuoteRepository.countBySource("tushare")));
        out.put("by_reliability", Map.of(
                "limited", indexQuoteRepository.countByReliability("limited"),
                "official", indexQuoteRepository.countByReliability("official")));
        out.put("status", total > 0 ? "已启用（东方财富指数日K，可靠度 limited）" : "未拉取（可通过 /api/data/index/fetch 拉取）");
        out.put("note", "接入沪深300/中证500/中证1000/上证指数，供市场环境分析与基准对比；"
                + "后续接入 Tushare 指数接口可替换为 official");
        return out;
    }

    private Map<String, Object> buildMissingTradingDays(LocalDate minDate, LocalDate maxDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (minDate == null || maxDate == null || minDate.isAfter(maxDate)) {
            m.put("expected", 0);
            m.put("actual", 0);
            m.put("missing_count", 0);
            m.put("missing_dates", List.of());
            m.put("note", "无行情数据");
            return m;
        }
        List<LocalDate> actual = marketDataRepository.findDistinctTradeDatesBetween(minDate, maxDate);
        // 预期交易日：自然日排除周末（未剔除法定节假日，会高估缺失）
        List<LocalDate> expected = new java.util.ArrayList<>();
        for (LocalDate d = minDate; !d.isAfter(maxDate); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                expected.add(d);
            }
        }
        java.util.Set<LocalDate> actualSet = new java.util.HashSet<>(actual);
        List<String> missing = expected.stream()
                .filter(d -> !actualSet.contains(d))
                .map(LocalDate::toString)
                .toList();
        m.put("expected", expected.size());
        m.put("actual", actual.size());
        m.put("missing_count", missing.size());
        m.put("missing_dates", missing.size() > 50 ? missing.subList(0, 50) : missing);
        m.put("note", "预期交易日按自然日排除周末估算，未剔除法定节假日；missing_count 偏高属正常");
        return m;
    }

    private Map<String, Object> buildStaleness(LocalDate maxDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        m.put("today", today.toString());
        if (maxDate == null) {
            m.put("days_behind", null);
            m.put("stale", true);
            m.put("warning", "无行情数据");
            return m;
        }
        long daysBehind = ChronoUnit.DAYS.between(maxDate, today);
        m.put("latest_date", maxDate.toString());
        m.put("days_behind", daysBehind);
        boolean stale = daysBehind > STALE_DAYS_THRESHOLD;
        m.put("stale", stale);
        m.put("threshold_days", STALE_DAYS_THRESHOLD);
        if (stale) {
            m.put("warning", "最新行情距今 " + daysBehind + " 天，数据可能滞后（阈值 " + STALE_DAYS_THRESHOLD + " 天，覆盖周末）");
        } else {
            m.put("warning", null);
        }
        return m;
    }

    private Map<String, Object> buildHealth(Map<String, Object> missingDays, Map<String, Object> staleness, long listDateMissing) {
        boolean stale = Boolean.TRUE.equals(staleness.get("stale"));
        int missingCount = ((Number) missingDays.getOrDefault("missing_count", 0)).intValue();
        String status;
        if (stale || listDateMissing > 0) {
            status = "warn";
        } else if (missingCount > 0) {
            status = "warn";
        } else {
            status = "ok";
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", status);
        h.put("verdict", "ok".equals(status) ? "数据质量良好"
                : "warn".equals(status) ? "存在数据质量提示，请关注告警项" : "数据质量异常");
        return h;
    }
}
