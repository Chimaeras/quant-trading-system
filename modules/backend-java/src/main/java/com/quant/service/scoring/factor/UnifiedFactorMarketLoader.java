package com.quant.service.scoring.factor;

import com.quant.entity.MarketData;
import com.quant.entity.StockFinancialSnapshot;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.service.PointInTimeUniverseProvider;
import com.quant.service.scoring.FinancialSnapshotLoader;
import com.quant.service.scoring.ScoringConstants;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.TradabilityCheck;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「读一次、算一次特征」的统一数据装载器（L1 前置）。
 *
 * <p><b>为什么把它单独抽出来</b>：这是"一次打分、5 个策略入口"这一目标的技术支点。
 * 若每个策略各自读库、各自算均线/MACD/RSI，5 个策略就是 5 次全量重算——
 * 既慢，又可能因各策略取数口径不同而<b>对同一只股票算出不同特征</b>，
 * 那正是本次重构要消除的病灶（核验报告记录：同一只股票在不同方法里分数互不相容）。
 *
 * <p>因此本类保证：给定 {@code (tradeDate, lookbackDays, excludeST, minListingDays)}，
 * 行情与特征<b>只装载并计算一次</b>，结果可被任意多个策略共享。
 *
 * <p><b>可测的"只读一次"</b>：{@link Outcome} 里带 {@code loadCount}，
 * 它是本类实例上装载次数的累计值。测试用它断言
 * "为 5 个策略产出结果时，装载只发生了一次"——而不是靠注释声称。
 */
@Component
public class UnifiedFactorMarketLoader {

    private final MarketDataRepository marketDataRepository;
    private final PointInTimeUniverseProvider pointInTimeUniverseProvider;
    private final FinancialSnapshotLoader financialSnapshotLoader;

    /**
     * 装载次数计数。用 {@link AtomicLong} 而非普通字段，因为装载可能被并发调用
     * （打分任务有单线程执行器，但读服务可能被 HTTP 并发触发）。
     * 计数只增不减，语义是"本进程内累计装载次数"，供测试与诊断使用。
     */
    private final AtomicLong loadCount = new AtomicLong();

    public UnifiedFactorMarketLoader(
            MarketDataRepository marketDataRepository,
            PointInTimeUniverseProvider pointInTimeUniverseProvider,
            FinancialSnapshotLoader financialSnapshotLoader) {
        this.marketDataRepository = marketDataRepository;
        this.pointInTimeUniverseProvider = pointInTimeUniverseProvider;
        this.financialSnapshotLoader = financialSnapshotLoader;
    }

    /**
     * 装载结果。
     *
     * @param tradeDate            实际使用的交易日（已按"不晚于请求日"回退到最近有数据的交易日）
     * @param universe             PIT universe 快照（含来源与可靠性披露）
     * @param features             通过可交易性与上市时长闸、且已完成 L1 特征计算的股票
     * @param tradabilityRejections 股票键 -> 可交易性拒绝码
     * @param tradabilityCounts    拒绝码 -> 计数
     * @param tradabilityRejected  被可交易性拒绝的总数
     * @param listingRejected      被上市时长拒绝的总数
     * @param candidateCount       进入闸门的候选股票总数（含被拒）
     * @param loadCount            本次调用后本装载器的累计装载次数
     * @param dataAvailable        是否取到行情（false 表示库中该区间无数据，调用方应据此给出空结果）
     */
    public record Outcome(
            LocalDate tradeDate,
            PointInTimeUniverseProvider.UniverseView universe,
            List<FeatureVector> features,
            Map<String, String> tradabilityRejections,
            Map<String, Integer> tradabilityCounts,
            int tradabilityRejected,
            int listingRejected,
            int candidateCount,
            long loadCount,
            boolean dataAvailable
    ) {}

    /** 用请求参数装载；参数解析口径与 {@code UnifiedFactorScoringService} 完全一致。 */
    @Transactional(readOnly = true)
    public Outcome load(Map<String, Object> req) {
        LocalDate reqDate = ScoringUtils.parseDate(req.get("tradeDate"));
        LocalDate tradeDate = reqDate != null
                ? marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate)
                : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());

        int lookbackDays = ScoringUtils.parseInt(req.get("lookbackDays"),
                ScoringConstants.DEFAULT_LOOKBACK_DAYS, 60, 2000);
        boolean excludeST = ScoringUtils.parseBool(req.get("excludeST"), true);
        int minListingDays = ScoringUtils.parseInt(req.get("minListingDays"), 60, 0, 3650);

        return load(tradeDate, lookbackDays, excludeST, minListingDays);
    }

    @Transactional(readOnly = true)
    public Outcome load(LocalDate tradeDate, int lookbackDays, boolean excludeST, int minListingDays) {
        PointInTimeUniverseProvider.UniverseView universe =
                pointInTimeUniverseProvider.loadAsOf(tradeDate);

        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        List<MarketData> allBars = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
        if (allBars.isEmpty()) {
            return new Outcome(tradeDate, universe, List.of(), Map.of(), Map.of(),
                    0, 0, 0, loadCount.incrementAndGet(), false);
        }

        Map<String, List<MarketData>> grouped = new LinkedHashMap<>();
        for (MarketData m : allBars) {
            grouped.computeIfAbsent(key(m.getStockCode(), m.getExchangeType()),
                    k -> new ArrayList<>(600)).add(m);
        }

        Map<String, StockInfo> infoMap = universe.byKey();
        Map<String, StockFinancialSnapshot> snapshotMap =
                financialSnapshotLoader.loadLatestByStockKey(tradeDate);

        List<FeatureVector> features = new ArrayList<>(grouped.size());
        Map<String, String> tradabilityRejections = new LinkedHashMap<>();
        Map<String, Integer> tradabilityCounts = new ConcurrentHashMap<>();
        AtomicInteger tradabilityRejected = new AtomicInteger();
        AtomicInteger listingRejected = new AtomicInteger();

        for (Map.Entry<String, List<MarketData>> e : grouped.entrySet()) {
            String k = e.getKey();
            List<MarketData> bars = e.getValue();
            if (bars == null || bars.isEmpty()) {
                continue;
            }
            StockInfo info = infoMap.get(k);
            MarketData signalBar = bars.get(bars.size() - 1);

            if (minListingDays > 0 && info != null && info.getListDate() != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(info.getListDate(), tradeDate);
                if (days < minListingDays) {
                    listingRejected.incrementAndGet();
                    tradabilityCounts.merge("LISTING_DAYS_TOO_SHORT", 1, Integer::sum);
                    continue;
                }
            }

            TradabilityCheck check = ScoringUtils.evaluateTradabilityStrict(
                    bars,
                    signalBar.getStockCode(),
                    signalBar.getExchangeType(),
                    info != null ? info.getStockName() : null,
                    info != null ? info.getStatus() : null,
                    excludeST,
                    true,
                    true);
            if (!check.isTradable()) {
                tradabilityRejected.incrementAndGet();
                tradabilityCounts.merge(check.code(), 1, Integer::sum);
                tradabilityRejections.putIfAbsent(k, check.code());
                continue;
            }

            // 财务快照必须在 compute 里传入：基本面因子的"可用性"是在 compute 内登记的，
            // 事后调 applyFinancials 只会写值、不会把因子标为可用（原实现即此缺陷）。
            StockFinancialSnapshot snap = snapshotMap.get(k);
            FactorCalculator.Outcome outcome = FactorCalculator.compute(
                    signalBar.getStockCode(),
                    signalBar.getExchangeType(),
                    info != null ? info.getStockName() : null,
                    tradeDate,
                    bars,
                    info != null ? info.getIndustry() : null,
                    snap);

            features.add(outcome.features());
        }

        return new Outcome(tradeDate, universe, features, tradabilityRejections,
                new LinkedHashMap<>(tradabilityCounts), tradabilityRejected.get(),
                listingRejected.get(), grouped.size(), loadCount.incrementAndGet(), true);
    }

    /** 本进程内累计装载次数（诊断/测试用）。 */
    public long loadCount() {
        return loadCount.get();
    }

    private static String key(String stockCode, String exchangeType) {
        return stockCode + "|" + exchangeType;
    }
}

