package com.quant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.PortfolioBacktestRun;
import com.quant.entity.PortfolioNavPoint;
import com.quant.entity.PortfolioOrder;
import com.quant.entity.PortfolioPosition;
import com.quant.entity.PredictionRun;
import com.quant.entity.PredictionSnapshotItem;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.exception.BusinessException;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.PortfolioBacktestRunRepository;
import com.quant.repository.PortfolioNavPointRepository;
import com.quant.repository.PortfolioOrderRepository;
import com.quant.repository.PortfolioPositionRepository;
import com.quant.repository.PredictionRunRepository;
import com.quant.repository.PredictionSnapshotItemRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.research.ResearchConvention;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 事件驱动组合回测（MP-015 / V2-MP-005 / V2-MP-020）：现金、等权、仓位上限、T+1 卖出、ResearchConvention 成本。
 *
 * <p>V2-MP-005 修复（Batch1）：
 * <ul>
 *   <li>同一交易日按市场时间排序：开盘 BUY 先于收盘 SELL；开盘买入只能用「前收盘后」已到账现金，
 *       绝不能用当日收盘才卖出的未来现金（现金因果 P0 修复）。</li>
 *   <li>执行价一律取自 {@code market_price_raw}，raw 缺失即拒单并记录原因，禁止静默回退 qfq。</li>
 *   <li>佣金含最低值（A 股最低 5 元）、印花税、滑点，方向正确。</li>
 *   <li>结束日为未能平仓的仓位输出明确终态（UNRESOLVED/OPEN）并单列未实现损益。</li>
 * </ul>
 *
 * <p>V2-MP-020 变更：
 * <ul>
 *   <li>规范化存储：订单/NAV/持仓落子表（portfolio_order/portfolio_nav_point/portfolio_position），
 *       不再写入单行 LONGTEXT；summary 单独存 summary_json（含资金守恒审计/拒绝漏斗/lineage）。</li>
 *   <li>run 异步化：POST /run 校验后落 RUNNING 行，提交 {@code scoringTaskExecutor} 异步计算，
 *       页面轮询 /{runId}/status；进度按已落 NAV 点数/预期交易日估算。</li>
 *   <li>predictionRunId 输入模式：从已发布 prediction snapshot 闭环取信号（封闭股票池），
 *       日期/horizon/track/topN 由 run 派生，不允许与用户输入冲突。</li>
 *   <li>资金守恒审计：cash_audit 校验 initial_cash - Σbuy(含佣金) + Σsell(净到账) = final cash。</li>
 * </ul>
 */
@Slf4j
@Service
public class PortfolioBacktestService {

    private static final String EXECUTION_VERSION = "portfolio_event_v2";
    private static final String STORAGE_VERSION = "portfolio_normalized_v1";
    private static final int LOT_SIZE = 100;
    /** A 股最低佣金（元）。 */
    private static final double MIN_COMMISSION = 5.0;
    /** 每 N 个交易日批量落 NAV 并刷一次进度（进度估算粒度）。 */
    private static final int NAV_FLUSH_EVERY = 10;
    /** 每 N 条订单批量落库（避免长区间内存/事务过大）。 */
    private static final int ORDER_FLUSH_EVERY = 200;

    private final PortfolioBacktestRunRepository portfolioBacktestRunRepository;
    private final PortfolioOrderRepository portfolioOrderRepository;
    private final PortfolioNavPointRepository portfolioNavPointRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final PredictionRunRepository predictionRunRepository;
    private final PredictionSnapshotItemRepository predictionSnapshotItemRepository;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final TradingCalendarService tradingCalendarService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor portfolioTaskExecutor;

    /** 正在执行/排队的 runId（防止同一 runId 重复入队）。 */
    private final Set<String> runningRunIds = ConcurrentHashMap.newKeySet();

    public PortfolioBacktestService(
            PortfolioBacktestRunRepository portfolioBacktestRunRepository,
            PortfolioOrderRepository portfolioOrderRepository,
            PortfolioNavPointRepository portfolioNavPointRepository,
            PortfolioPositionRepository portfolioPositionRepository,
            SelectorResultRepository selectorResultRepository,
            SelectorResultItemRepository selectorResultItemRepository,
            PredictionRunRepository predictionRunRepository,
            PredictionSnapshotItemRepository predictionSnapshotItemRepository,
            MarketDataRepository marketDataRepository,
            MarketPriceRawRepository marketPriceRawRepository,
            TradingCalendarService tradingCalendarService,
            ObjectMapper objectMapper,
            @Qualifier("scoringTaskExecutor") ThreadPoolTaskExecutor portfolioTaskExecutor) {
        this.portfolioBacktestRunRepository = portfolioBacktestRunRepository;
        this.portfolioOrderRepository = portfolioOrderRepository;
        this.portfolioNavPointRepository = portfolioNavPointRepository;
        this.portfolioPositionRepository = portfolioPositionRepository;
        this.selectorResultRepository = selectorResultRepository;
        this.selectorResultItemRepository = selectorResultItemRepository;
        this.predictionRunRepository = predictionRunRepository;
        this.predictionSnapshotItemRepository = predictionSnapshotItemRepository;
        this.marketDataRepository = marketDataRepository;
        this.marketPriceRawRepository = marketPriceRawRepository;
        this.tradingCalendarService = tradingCalendarService;
        this.objectMapper = objectMapper;
        this.portfolioTaskExecutor = portfolioTaskExecutor;
    }

    /**
     * 应用启动恢复：崩溃后 RUNNING 行无 worker 附着（内存 runningRunIds 重启清空），
     * 批量标记为 FAILED。组合回测无 PENDING 中间态（submitRun 直接落 RUNNING），故仅处理 RUNNING 孤儿。
     * 回测参数已持久化在 PortfolioBacktestRun 行内，用户可据 error 提示重新提交。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverOnStartup() {
        try {
            int orphaned = portfolioBacktestRunRepository.markOrphanedRunsFailed(
                    "应用重启恢复：原 RUNNING 状态无 worker 附着，标记 FAILED，请重新提交", LocalDateTime.now());
            runningRunIds.clear();
            if (orphaned > 0) {
                log.warn("组合回测启动恢复: {} 个孤儿 RUNNING 行已标记 FAILED，请重新提交", orphaned);
            }
        } catch (Exception e) {
            log.warn("组合回测启动恢复失败（DB 可能未就绪）: {}", e.getMessage());
        }
    }

    // ========================================================================
    // 提交（异步受理，202 语义）
    // ========================================================================

    /**
     * 校验参数、落 RUNNING 行并提交异步计算，立即返回 runId。
     * predictionRunId 模式下，start/end/horizon/track/topN 由已发布 prediction run 派生。
     */
    @Transactional
    public Map<String, Object> submitRun(Map<String, Object> request) {
        String predictionRunId = strNullable(request.get("predictionRunId"));
        ResolvedParams p = resolveParams(request, predictionRunId);

        String runId = "PBT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        PortfolioBacktestRun entity = new PortfolioBacktestRun();
        entity.setRunId(runId);
        entity.setPredictionRunId(p.predictionRunId());
        entity.setTrack(p.track());
        entity.setStrategyType(p.strategyType());
        entity.setStartDate(p.startDate());
        entity.setEndDate(p.endDate());
        entity.setHorizon(p.horizon());
        entity.setTopN(p.topN());
        entity.setInitialCash(p.initialCash());
        entity.setMaxPositions(p.maxPositions());
        entity.setWeighting("EQUAL");
        entity.setCommissionRate(p.commissionRate());
        entity.setSellTaxRate(p.sellTaxRate());
        entity.setSlippageRate(p.slippageRate());
        entity.setCostVersion(ResearchConvention.CONVENTION_VERSION);
        entity.setExecutionVersion(EXECUTION_VERSION);
        entity.setStatus("RUNNING");
        entity.setProgress(0);
        portfolioBacktestRunRepository.save(entity);

        scheduleExecution(runId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("status", "RUNNING");
        out.put("prediction_run_id", p.predictionRunId());
        out.put("strategy_type", p.strategyType());
        out.put("start_date", p.startDate().toString());
        out.put("end_date", p.endDate().toString());
        out.put("horizon", p.horizon());
        out.put("track", p.track());
        out.put("top_n", p.topN());
        out.put("signal_source", p.predictionRunId() != null ? "PREDICTION_RUN" : "SELECTOR_RESULT");
        out.put("note", "已受理异步组合回测，轮询 GET /backtest/portfolio/{runId}/status 直至 COMPLETED/FAILED");
        return out;
    }

    private void scheduleExecution(String runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitExecutor(runId);
                }
            });
        } else {
            submitExecutor(runId);
        }
    }

    private void submitExecutor(String runId) {
        if (!runningRunIds.add(runId)) {
            return;
        }
        portfolioTaskExecutor.submit(() -> executeRun(runId));
    }

    private void executeRun(String runId) {
        try {
            simulateAndPersist(runId);
        } catch (Exception e) {
            log.error("组合回测执行失败 runId={}", runId, e);
            try {
                String msg = e.getMessage();
                if (msg != null && msg.length() > 450) {
                    msg = msg.substring(0, 450);
                }
                portfolioBacktestRunRepository.markFailed(runId, "FAILED", msg, LocalDateTime.now());
            } catch (Exception ex) {
                log.error("组合回测失败状态写入异常 runId={}", runId, ex);
            }
        } finally {
            runningRunIds.remove(runId);
        }
    }

    // ========================================================================
    // 参数解析
    // ========================================================================

    private record ResolvedParams(
            String predictionRunId,
            String strategyType,
            String track,
            LocalDate startDate,
            LocalDate endDate,
            int horizon,
            int topN,
            double initialCash,
            int maxPositions,
            double commissionRate,
            double sellTaxRate,
            double slippageRate) {}

    private ResolvedParams resolveParams(Map<String, Object> request, String predictionRunId) {
        double initialCash = doubleVal(request.get("initialCash"), 1_000_000.0);
        int maxPositions = Math.max(1, intVal(request.get("maxPositions"), 5));
        double commissionRate = doubleVal(request.get("commissionRate"), ResearchConvention.DEFAULT_COMMISSION_RATE);
        double sellTaxRate = doubleVal(request.get("sellTaxRate"), ResearchConvention.DEFAULT_SELL_TAX_RATE);
        double slippageRate = doubleVal(request.get("slippageRate"), ResearchConvention.DEFAULT_SLIPPAGE_RATE);

        if (predictionRunId != null && !predictionRunId.isBlank()) {
            // 闭环模式：参数由已发布 prediction run 派生，禁止用户传入冲突的日期/horizon/track
            PredictionRun pred = predictionRunRepository.findByRunId(predictionRunId.trim())
                    .orElseThrow(() -> new BusinessException("prediction run 不存在: " + predictionRunId));
            if (!PredictionRun.STATUS_PUBLISHED.equals(pred.getStatus())) {
                throw new BusinessException("仅支持已发布 prediction run（当前 status=" + pred.getStatus() + "）");
            }
            if (pred.getTradeDate() == null || pred.getHorizon() == null) {
                throw new BusinessException("prediction run 缺少 trade_date/horizon，无法派生回测窗口");
            }
            int topN = Math.max(1, intVal(request.get("topN"), Math.max(1, pred.getItemCount() != null ? pred.getItemCount() : 1)));
            LocalDate endDate = resolveRunEndDate(pred.getTradeDate(), pred.getHorizon());
            return new ResolvedParams(
                    pred.getRunId(),
                    pred.getStrategyType() != null && !pred.getStrategyType().isBlank() ? pred.getStrategyType() : "CLASSIC_SELECTOR",
                    pred.getTrack(),
                    pred.getTradeDate(),
                    endDate,
                    pred.getHorizon(),
                    topN,
                    initialCash, maxPositions, commissionRate, sellTaxRate, slippageRate);
        }

        // 区间模式：selector_result 信号源（保持 V9 语义）
        LocalDate startDate = parseDate(request.get("startDate"), "startDate");
        LocalDate endDate = parseDate(request.get("endDate"), "endDate");
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("endDate 不能早于 startDate");
        }
        String strategyType = str(request.get("strategyType"), "CLASSIC_SELECTOR");
        int horizon = intVal(request.get("horizon"), 2);
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon) && horizon != 1) {
            throw new BusinessException("horizon 仅支持 1/2/3");
        }
        int topN = Math.max(1, intVal(request.get("topN"), maxPositions));
        return new ResolvedParams(
                null, strategyType, null, startDate, endDate, horizon, topN,
                initialCash, maxPositions, commissionRate, sellTaxRate, slippageRate);
    }

    /** 闭环模式结束日：信号日 T 之后 T+1 入场、持有 horizon 个交易日后的退出日。 */
    private LocalDate resolveRunEndDate(LocalDate signalDate, int horizon) {
        LocalDate probeEnd = signalDate.plusDays(horizon * 3L + 15);
        List<LocalDate> days = tradingCalendarService.findTradingDays(signalDate, probeEnd);
        if (days.isEmpty()) {
            days = marketDataRepository.findDistinctTradeDatesBetween(signalDate, probeEnd);
        }
        if (days.isEmpty()) {
            return probeEnd;
        }
        LocalDate entry = nextTradingDay(days, signalDate);
        if (entry == null) {
            return probeEnd;
        }
        LocalDate exit = tradingDayOffset(days, entry, horizon);
        return exit != null ? exit : days.get(days.size() - 1);
    }

    // ========================================================================
    // 事件模拟 + 规范化持久化
    // ========================================================================

    private void simulateAndPersist(String runId) {
        PortfolioBacktestRun run = portfolioBacktestRunRepository.findByRunId(runId).orElse(null);
        if (run == null) {
            return;
        }
        String predictionRunId = run.getPredictionRunId();
        String strategyType = run.getStrategyType();
        String track = run.getTrack();
        LocalDate startDate = run.getStartDate();
        LocalDate endDate = run.getEndDate();
        int horizon = run.getHorizon();
        int topN = run.getTopN() != null ? run.getTopN() : Math.max(1, run.getMaxPositions());
        double initialCash = run.getInitialCash();
        int maxPositions = run.getMaxPositions();
        double commissionRate = run.getCommissionRate();
        double sellTaxRate = run.getSellTaxRate();
        double slippageRate = run.getSlippageRate();

        LocalDate priceEnd = endDate.plusDays(horizon * 3L + 30);
        List<LocalDate> tradingDays = tradingCalendarService.findTradingDays(startDate, priceEnd);
        if (tradingDays.isEmpty()) {
            tradingDays = marketDataRepository.findDistinctTradeDatesBetween(startDate, priceEnd);
        }
        if (tradingDays.isEmpty()) {
            throw new BusinessException("区间内无交易日");
        }
        int expectedDays = 0;
        for (LocalDate d : tradingDays) {
            if (!d.isBefore(startDate) && !d.isAfter(endDate)) {
                expectedDays++;
            }
        }
        expectedDays = Math.max(1, expectedDays);

        Map<LocalDate, List<Signal>> signalsByDate =
                buildSignals(strategyType, startDate, endDate, topN, predictionRunId);

        Map<String, MarketPriceRaw> rawMap = new HashMap<>();
        for (MarketPriceRaw raw : marketPriceRawRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                        startDate.minusDays(5), priceEnd)) {
            rawMap.put(priceKey(raw.getStockCode(), raw.getExchangeType(), raw.getTradeDate()), raw);
        }
        Map<String, MarketData> qfqMap = new HashMap<>();
        for (MarketData md : marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                        startDate.minusDays(5), priceEnd)) {
            qfqMap.put(priceKey(md.getStockCode(), md.getExchangeType(), md.getTradeDate()), md);
        }

        double cash = initialCash;
        Map<String, Position> positions = new LinkedHashMap<>();
        // 每个持仓最近可得 mark 价（向前回溯），用于停牌期 NAV 标记，避免回退 entryPrice 冻结 NAV。
        Map<String, Double> lastKnownMark = new HashMap<>();
        Map<LocalDate, List<PendingBuy>> pendingBuys = new LinkedHashMap<>();

        List<PortfolioOrder> orderBuffer = new ArrayList<>();
        List<PortfolioNavPoint> navBuffer = new ArrayList<>();
        int seqCounter = 0;
        int flushedNavCount = 0;
        int rejectCount = 0;
        int tradeCount = 0;
        double peakNav = initialCash;
        double maxDrawdown = 0;
        double buyCashOut = 0;
        double sellCashIn = 0;
        double lastNav = initialCash;
        Map<String, Integer> rejectFunnel = new LinkedHashMap<>();

        LocalDate lastSim = tradingDays.get(tradingDays.size() - 1);

        for (LocalDate day : tradingDays) {
            if (day.isBefore(startDate) || day.isAfter(lastSim)) {
                continue;
            }

            // ============================================================
            // 1) 开盘 BUY（先于收盘 SELL 执行）——只能用「当日开盘前」已到账现金。
            //    这是 MP-005 P0 现金因果修复：绝不能用当日收盘才卖出的未来现金回填开盘成交。
            // ============================================================
            List<PendingBuy> buys = pendingBuys.remove(day);
            if (buys != null && !buys.isEmpty()) {
                List<PendingBuy> eligible = buys.stream()
                        .filter(b -> !positions.containsKey(b.key()))
                        .limit(Math.max(0, maxPositions - positions.size()))
                        .toList();
                int n = eligible.size();
                if (n > 0) {
                    DayBudgetGuard budget = new DayBudgetGuard(cash, n);
                    for (PendingBuy b : eligible) {
                        // 等权分配但须动态反映剩余：若前几笔被拒(未花钱)或买得便宜，剩余现金变大，
                        // 后续标的可用钱也应增加，否则固定 budgetEach=cash/n 会让高价标的被误拒。
                        // 每笔先按「当前剩余现金/剩余待买数量」确定预算；最终 totalCost>cash 兜底保证不超支。
                        double budgetEach = budget.budgetForNext();
                        PriceBar bar = resolveExecutionBar(b.stockCode, b.exchangeType, day, rawMap);
                        if (bar == null || bar.open == null || bar.open <= 0 || bar.zeroVolume) {
                            String rejectReason = bar == null ? "NO_QUOTE_RAW_MISSING" : "NOT_BUYABLE";
                            PortfolioOrder o = rejectBuy(runId, ++seqCounter, day, b, rejectReason);
                            orderBuffer.add(o);
                            rejectCount++;
                            rejectFunnel.merge(rejectReason, 1, Integer::sum);
                            budget.onRejected();
                            continue;
                        }
                        double preClose = resolvePreClose(bar, b.stockCode, b.exchangeType, day, rawMap, qfqMap, tradingDays);
                        double limitPct = TradingLimitRules.resolveLimitPct(b.stockCode, b.exchangeType, b.stockName);
                        MarketData openBar = new MarketData();
                        openBar.setOpenPrice(BigDecimal.valueOf(bar.open));
                        if (StrategyBacktestEngine.isLimitUpAtOpen(openBar, preClose, limitPct)) {
                            orderBuffer.add(rejectBuy(runId, ++seqCounter, day, b, "LIMIT_UP"));
                            rejectCount++;
                            rejectFunnel.merge("LIMIT_UP", 1, Integer::sum);
                            budget.onRejected();
                            continue;
                        }
                        double entryPx = bar.open * (1 + slippageRate);
                        BuyDecision d = decideBuy(entryPx, commissionRate, budgetEach, budget.remainingCash());
                        if (!d.isFill()) {
                            orderBuffer.add(rejectBuy(runId, ++seqCounter, day, b, d.rejectReason()));
                            rejectCount++;
                            rejectFunnel.merge(d.rejectReason(), 1, Integer::sum);
                            budget.onRejected();
                            continue;
                        }
                        budget.onSpent(d.totalCost());
                        cash -= d.totalCost();
                        buyCashOut += d.totalCost();
                        positions.put(b.key(), new Position(
                                b.stockCode, b.exchangeType, b.stockName, d.shares(), entryPx, day, b.exitDate));
                        lastKnownMark.put(b.key(), entryPx);
                        PortfolioOrder o = fillBuy(runId, ++seqCounter, day, b, entryPx, d.shares(), d.gross(), d.totalCost(), d.commission(), bar.source);
                        orderBuffer.add(o);
                        tradeCount++;
                    }
                }
            }

            // ============================================================
            // 2) 收盘 SELL（T+1：entryDate 当日不可卖）。卖出所得现金当日收盘后才到账，
            //    只能用于后续交易日。
            // ============================================================
            List<String> toClose = new ArrayList<>();
            for (Map.Entry<String, Position> e : positions.entrySet()) {
                Position p = e.getValue();
                if (!day.isBefore(p.exitDate) && day.isAfter(p.entryDate)) {
                    toClose.add(e.getKey());
                }
            }
            for (String key : toClose) {
                Position p = positions.remove(key);
                PriceBar bar = resolveExecutionBar(p.stockCode, p.exchangeType, day, rawMap);
                if (bar == null || bar.close == null || bar.close <= 0 || bar.zeroVolume) {
                    String rejectReason = bar == null ? "NO_QUOTE_RAW_MISSING" : "NOT_SELLABLE";
                    PortfolioOrder o = rejectOrder(runId, ++seqCounter, day, p, rejectReason);
                    orderBuffer.add(o);
                    rejectCount++;
                    rejectFunnel.merge(rejectReason, 1, Integer::sum);
                    positions.put(key, p);
                    continue;
                }
                double preClose = resolvePreClose(bar, p.stockCode, p.exchangeType, day, rawMap, qfqMap, tradingDays);
                double limitPct = TradingLimitRules.resolveLimitPct(p.stockCode, p.exchangeType, p.stockName);
                MarketData md = new MarketData();
                md.setClosePrice(BigDecimal.valueOf(bar.close));
                if (StrategyBacktestEngine.isLimitDownAtClose(md, preClose, limitPct)) {
                    PortfolioOrder o = rejectOrder(runId, ++seqCounter, day, p, "LIMIT_DOWN");
                    orderBuffer.add(o);
                    rejectCount++;
                    rejectFunnel.merge("LIMIT_DOWN", 1, Integer::sum);
                    positions.put(key, p);
                    continue;
                }
                double exitPx = bar.close * (1 - slippageRate);
                double gross = exitPx * p.shares;
                double commission = Math.max(gross * commissionRate, MIN_COMMISSION);
                double stampTax = gross * sellTaxRate;
                double proceeds = gross - commission - stampTax;
                cash += proceeds;
                sellCashIn += proceeds;
                PortfolioOrder o = fillSell(runId, ++seqCounter, day, p, exitPx, gross, proceeds, commission, stampTax, bar.source);
                orderBuffer.add(o);
                tradeCount++;
                lastKnownMark.remove(key);
            }

            // --- schedule new signals ---
            if (!day.isAfter(endDate) && signalsByDate.containsKey(day)) {
                LocalDate entryDate = nextTradingDay(tradingDays, day);
                LocalDate exitDate = tradingDayOffset(tradingDays, entryDate, horizon);
                if (entryDate != null && exitDate != null) {
                    int pendingCount = pendingBuys.values().stream().mapToInt(List::size).sum();
                    int slots = maxPositions - positions.size() - pendingCount;
                    if (slots > 0) {
                        List<PendingBuy> batch = new ArrayList<>();
                        for (Signal s : signalsByDate.get(day)) {
                            if (batch.size() >= slots) {
                                break;
                            }
                            String key = s.stockCode() + "|" + s.exchangeType();
                            if (positions.containsKey(key)) {
                                continue;
                            }
                            boolean alreadyPending = pendingBuys.values().stream()
                                    .flatMap(List::stream).anyMatch(pb -> pb.key().equals(key));
                            if (alreadyPending) {
                                continue;
                            }
                            batch.add(new PendingBuy(
                                    s.stockCode(), s.exchangeType(), s.stockName(),
                                    day, entryDate, exitDate));
                        }
                        if (!batch.isEmpty()) {
                            pendingBuys.computeIfAbsent(entryDate, k -> new ArrayList<>()).addAll(batch);
                        }
                    }
                }
            }

            // --- NAV（标记价：优先 raw close，缺失时用最近可得历史 mark，非 entryPrice） ---
            double posValue = 0;
            for (Position p : positions.values()) {
                String posKey = p.stockCode + "|" + p.exchangeType;
                PriceBar bar = resolvePriceBar(p.stockCode, p.exchangeType, day, rawMap, qfqMap);
                double px;
                if (bar != null && bar.close != null && bar.close > 0) {
                    px = bar.close;
                    lastKnownMark.put(posKey, px);
                } else {
                    // 停牌/缺价：沿用最近可得的历史 mark 价，而非 entryPrice（成本价）。
                    // 回退 entryPrice 会把 NAV 冻结在成本线，系统性低估 maxDrawdown。
                    Double lastMark = lastKnownMark.get(posKey);
                    px = lastMark != null ? lastMark : p.entryPrice;
                }
                posValue += px * p.shares;
            }
            double nav = cash + posValue;
            peakNav = Math.max(peakNav, nav);
            if (peakNav > 0) {
                maxDrawdown = Math.max(maxDrawdown, (peakNav - nav) / peakNav);
            }
            if (!day.isAfter(endDate) || !positions.isEmpty()) {
                lastNav = nav;
                PortfolioNavPoint np = new PortfolioNavPoint();
                np.setRunId(runId);
                np.setTradeDate(day);
                np.setCash(round4(cash));
                np.setPositionValue(round4(posValue));
                np.setNav(round4(nav));
                np.setPositions(positions.size());
                navBuffer.add(np);
            }

            // --- 批量落库 + 进度心跳 ---
            if (navBuffer.size() >= NAV_FLUSH_EVERY) {
                portfolioNavPointRepository.saveAll(navBuffer);
                flushedNavCount += navBuffer.size();
                navBuffer.clear();
                int progress = Math.min(99, Math.max(1, (int) Math.round(100.0 * flushedNavCount / expectedDays)));
                portfolioBacktestRunRepository.updateProgressIf(runId, "RUNNING", progress, LocalDateTime.now());
            }
            if (orderBuffer.size() >= ORDER_FLUSH_EVERY) {
                portfolioOrderRepository.saveAll(orderBuffer);
                orderBuffer.clear();
            }
        }

        // ============================================================
        // 3) 期末持仓终态：未平仓仓位输出明确状态与未实现损益（MP-005）。
        // ============================================================
        List<PortfolioPosition> endPositions = new ArrayList<>();
        double unrealizedPnl = 0;
        for (Position p : positions.values()) {
            double markPx = p.entryPrice;
            String markSource = "STALE_ENTRY";
            PriceBar bar = resolvePriceBar(p.stockCode, p.exchangeType, lastSim, rawMap, qfqMap);
            if (bar != null && bar.close != null && bar.close > 0) {
                markPx = bar.close;
                markSource = bar.source;
            }
            double pnl = (markPx - p.entryPrice) * p.shares;
            unrealizedPnl += pnl;
            PortfolioPosition pos = new PortfolioPosition();
            pos.setRunId(runId);
            pos.setStockCode(p.stockCode);
            pos.setExchangeType(p.exchangeType);
            pos.setStockName(p.stockName);
            pos.setShares(p.shares);
            pos.setEntryPrice(round4(p.entryPrice));
            pos.setMarkPrice(round4(markPx));
            pos.setEntryDate(p.entryDate);
            pos.setExitDate(p.exitDate);
            pos.setFinalStatus(lastSim.isBefore(p.exitDate) ? "OPEN_NOT_YET_EXPIRED" : "OPEN_UNRESOLVED");
            pos.setUnrealizedPnl(round4(pnl));
            pos.setPriceSource(markSource);
            endPositions.add(pos);
        }
        int unresolvedCount = (int) endPositions.stream()
                .filter(pp -> "OPEN_UNRESOLVED".equals(pp.getFinalStatus())).count();

        double finalNav = lastNav;
        double totalReturn = initialCash > 0 ? finalNav / initialCash - 1 : 0;

        // 资金守恒审计（V2-MP-020）：final_cash = initial - Σbuy(含佣金) + Σsell(净到账)
        double computedFinalCash = initialCash - buyCashOut + sellCashIn;
        boolean cashBalanced = Math.abs(computedFinalCash - cash) < 0.01;
        Map<String, Object> cashAudit = new LinkedHashMap<>();
        cashAudit.put("initial_cash", round4(initialCash));
        cashAudit.put("buy_cash_out", round4(buyCashOut));
        cashAudit.put("sell_cash_in", round4(sellCashIn));
        cashAudit.put("computed_final_cash", round4(computedFinalCash));
        cashAudit.put("actual_final_cash", round4(cash));
        cashAudit.put("balanced", cashBalanced);
        cashAudit.put("note", "资金守恒：final_cash = initial_cash - Σbuy(含佣金) + Σsell(净到账)；"
                + "不守恒即回测引擎缺陷");

        Map<String, Object> predictionLineage = null;
        if (predictionRunId != null && !predictionRunId.isBlank()) {
            PredictionRun pred = predictionRunRepository.findByRunId(predictionRunId).orElse(null);
            if (pred != null) {
                predictionLineage = new LinkedHashMap<>();
                predictionLineage.put("prediction_run_id", pred.getRunId());
                predictionLineage.put("trade_date", pred.getTradeDate().toString());
                predictionLineage.put("horizon", pred.getHorizon());
                predictionLineage.put("track", pred.getTrack());
                predictionLineage.put("publish_version", pred.getPublishVersion());
                predictionLineage.put("status", pred.getStatus());
                predictionLineage.put("model_version", pred.getModelVersion());
                predictionLineage.put("factor_version", pred.getFactorVersion());
                predictionLineage.put("strategy_type", pred.getStrategyType());
                predictionLineage.put("selector_result_id", pred.getSelectorResultId());
                predictionLineage.put("item_count", pred.getItemCount());
                predictionLineage.put("lineage", parseJson(pred.getLineageJson()));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("strategy_type", strategyType);
        summary.put("track", track);
        summary.put("prediction_run_id", predictionRunId);
        summary.put("start_date", startDate.toString());
        summary.put("end_date", endDate.toString());
        summary.put("horizon", horizon);
        summary.put("initial_cash", initialCash);
        summary.put("max_positions", maxPositions);
        summary.put("top_n", topN);
        summary.put("weighting", "EQUAL");
        summary.put("final_nav", round4(finalNav));
        summary.put("total_return", round4(totalReturn));
        summary.put("max_drawdown", round4(maxDrawdown));
        summary.put("total_trades", tradeCount);
        summary.put("reject_count", rejectCount);
        summary.put("reject_funnel", rejectFunnel);
        summary.put("cost_version", ResearchConvention.CONVENTION_VERSION);
        summary.put("execution_version", EXECUTION_VERSION);
        summary.put("storage_version", STORAGE_VERSION);
        summary.put("commission_rate", commissionRate);
        summary.put("sell_tax_rate", sellTaxRate);
        summary.put("slippage_rate", slippageRate);
        summary.put("t_plus_one", ResearchConvention.T_PLUS_ONE_SELL);
        summary.put("final_position_count", endPositions.size());
        summary.put("final_open_status", endPositions.isEmpty() ? "CLOSED"
                : unresolvedCount > 0 ? "OPEN_UNRESOLVED" : "OPEN_NOT_YET_EXPIRED");
        summary.put("unrealized_pnl", round4(unrealizedPnl));
        summary.put("cash_audit", cashAudit);
        summary.put("signal_source", predictionRunId != null ? "PREDICTION_RUN" : "SELECTOR_RESULT");
        summary.put("expected_trading_days", expectedDays);
        if (predictionLineage != null) {
            summary.put("prediction_lineage", predictionLineage);
        }
        summary.put("note", "事件驱动组合回测：等权、仓位上限、T+1、整手、同一交易日开盘BUY先于收盘SELL"
                + "（MP-005 现金因果）、成交仅用 raw 价、最低佣金" + MIN_COMMISSION + "元/笔；期末持仓输出显式终态；"
                + "订单/NAV/持仓规范化存储（MP-020）");

        // ---- 终态落库：剩余 NAV/订单 + 期末持仓 + summary + COMPLETED ----
        if (!navBuffer.isEmpty()) {
            portfolioNavPointRepository.saveAll(navBuffer);
            navBuffer.clear();
        }
        if (!orderBuffer.isEmpty()) {
            portfolioOrderRepository.saveAll(orderBuffer);
            orderBuffer.clear();
        }
        if (!endPositions.isEmpty()) {
            portfolioPositionRepository.saveAll(endPositions);
        }
        run.setFinalNav(round4(finalNav));
        run.setTotalReturn(round4(totalReturn));
        run.setMaxDrawdown(round4(maxDrawdown));
        run.setTotalTrades(tradeCount);
        run.setRejectCount(rejectCount);
        run.setSummaryJson(writeJson(summary));
        run.setProgress(100);
        run.setStatus("COMPLETED");
        run.setUpdatedAt(LocalDateTime.now());
        portfolioBacktestRunRepository.save(run);
    }

    // ========================================================================
    // 查询
    // ========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String runId) {
        PortfolioBacktestRun run = findByRunIdOrThrow(runId);
        Map<String, Object> out = parseMap(run.getSummaryJson());
        if (out.isEmpty()) {
            out = new LinkedHashMap<>();
            out.put("run_id", run.getRunId());
            out.put("strategy_type", run.getStrategyType());
            out.put("start_date", run.getStartDate() != null ? run.getStartDate().toString() : null);
            out.put("end_date", run.getEndDate() != null ? run.getEndDate().toString() : null);
            out.put("horizon", run.getHorizon());
            out.put("initial_cash", run.getInitialCash());
            out.put("max_positions", run.getMaxPositions());
            out.put("top_n", run.getTopN());
            out.put("final_nav", run.getFinalNav());
            out.put("total_return", run.getTotalReturn());
            out.put("max_drawdown", run.getMaxDrawdown());
            out.put("total_trades", run.getTotalTrades());
            out.put("reject_count", run.getRejectCount());
        }
        out.put("run_id", run.getRunId());
        out.put("status", run.getStatus());
        out.put("progress", run.getProgress() != null ? run.getProgress() : 0);
        out.put("error_message", run.getErrorMessage());
        out.put("prediction_run_id", run.getPredictionRunId());
        out.put("storage_version", STORAGE_VERSION);
        out.put("created_at", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        out.put("updated_at", run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : null);

        List<PortfolioNavPoint> nav = portfolioNavPointRepository.findByRunIdOrderByTradeDateAsc(runId);
        out.put("nav", nav.stream().map(this::navMap).toList());
        out.put("nav_count", nav.size());

        List<PortfolioPosition> pos = portfolioPositionRepository.findByRunIdOrderByStockCodeAsc(runId);
        out.put("open_positions", pos.stream().map(this::positionMap).toList());
        out.put("positions_count", pos.size());
        out.put("orders_count", portfolioOrderRepository.countByRunId(runId));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRunStatus(String runId) {
        PortfolioBacktestRun run = findByRunIdOrThrow(runId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("status", run.getStatus());
        m.put("progress", run.getProgress() != null ? run.getProgress() : 0);
        m.put("error_message", run.getErrorMessage());
        m.put("updated_at", run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : null);
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOrders(String runId, int page, int size) {
        findByRunIdOrThrow(runId);
        int p = Math.max(1, page);
        int s = Math.min(500, Math.max(1, size));
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.ASC, "seq"));
        Page<PortfolioOrder> data = portfolioOrderRepository.findByRunIdOrderBySeqAsc(runId, pageable);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("page", p);
        out.put("size", s);
        out.put("total", data.getTotalElements());
        out.put("total_pages", data.getTotalPages());
        out.put("items", data.getContent().stream().map(this::orderMap).toList());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listRuns(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PortfolioBacktestRun> data = portfolioBacktestRunRepository.findAll(pageable);
        List<Map<String, Object>> items = data.getContent().stream().map(this::runSummaryMap).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("page", p);
        out.put("size", s);
        out.put("total", data.getTotalElements());
        out.put("total_pages", data.getTotalPages());
        return out;
    }

    private PortfolioBacktestRun findByRunIdOrThrow(String runId) {
        return portfolioBacktestRunRepository.findByRunId(runId)
                .orElseThrow(() -> new BusinessException("组合回测不存在: " + runId));
    }

    // ========================================================================
    // 信号源
    // ========================================================================

    private record Signal(String stockCode, String exchangeType, String stockName, LocalDate signalDate) {}

    private Map<LocalDate, List<Signal>> buildSignals(
            String strategyType, LocalDate start, LocalDate end, int topN, String predictionRunId
    ) {
        if (predictionRunId != null && !predictionRunId.isBlank()) {
            List<Signal> signals = predictionSnapshotItemRepository
                    .findByRunIdOrderByRankNoAsc(predictionRunId).stream()
                    .sorted(Comparator.comparingInt(i -> i.getRankNo() != null ? i.getRankNo() : Integer.MAX_VALUE))
                    .limit(topN)
                    .map(i -> new Signal(i.getStockCode(), i.getExchangeType(), i.getStockName(), start))
                    .toList();
            Map<LocalDate, List<Signal>> out = new LinkedHashMap<>();
            if (!signals.isEmpty()) {
                out.put(start, signals);
            }
            return out;
        }

        Map<LocalDate, List<Signal>> out = new LinkedHashMap<>();
        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(start, end).stream()
                .filter(r -> strategyType.equals(r.getStrategyType()))
                .filter(r -> "SUCCESS".equals(r.getRunStatus()))
                .toList();
        Map<LocalDate, SelectorResult> latest = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latest.putIfAbsent(r.getTradeDate(), r);
        }
        if (latest.isEmpty()) {
            return out;
        }
        List<String> ids = latest.values().stream().map(SelectorResult::getResultId).toList();
        Map<String, List<SelectorResultItem>> itemsByResult = selectorResultItemRepository
                .findByResultIdInOrderByRankNo(ids).stream()
                .collect(Collectors.groupingBy(SelectorResultItem::getResultId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<LocalDate, SelectorResult> e : latest.entrySet()) {
            List<Signal> signals = itemsByResult.getOrDefault(e.getValue().getResultId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(i -> i.getRankNo() != null ? i.getRankNo() : Integer.MAX_VALUE))
                    .limit(topN)
                    .map(i -> new Signal(i.getStockCode(), i.getExchangeType(), i.getStockName(), e.getKey()))
                    .toList();
            if (!signals.isEmpty()) {
                out.put(e.getKey(), signals);
            }
        }
        return out;
    }

    // ========================================================================
    // 订单/NAV/持仓构建
    // ========================================================================

    private PortfolioOrder baseOrder(String runId, int seq, String action, LocalDate day, String code, String exch, String name) {
        PortfolioOrder o = new PortfolioOrder();
        o.setRunId(runId);
        o.setSeq(seq);
        o.setAction(action);
        o.setTradeDate(day);
        o.setStockCode(code);
        o.setExchangeType(exch);
        o.setStockName(name);
        return o;
    }

    private PortfolioOrder fillBuy(String runId, int seq, LocalDate day, PendingBuy b,
                                   double entryPx, int shares, double gross, double totalCost,
                                   double commission, String priceSource) {
        PortfolioOrder o = baseOrder(runId, seq, "BUY", day, b.stockCode, b.exchangeType, b.stockName);
        o.setStatus("FILLED");
        o.setPrice(round4(entryPx));
        o.setShares(shares);
        o.setAmount(round4(totalCost));
        o.setGrossAmount(round4(gross));
        o.setCommission(round4(commission));
        o.setPriceSource(priceSource);
        o.setSignalDate(b.signalDate);
        o.setExitDate(b.exitDate);
        o.setEventTime("OPEN");
        return o;
    }

    private PortfolioOrder fillSell(String runId, int seq, LocalDate day, Position p,
                                    double exitPx, double gross, double proceeds,
                                    double commission, double stampTax, String priceSource) {
        PortfolioOrder o = baseOrder(runId, seq, "SELL", day, p.stockCode, p.exchangeType, p.stockName);
        o.setStatus("FILLED");
        o.setPrice(round4(exitPx));
        o.setShares(p.shares);
        o.setAmount(round4(proceeds));
        o.setGrossAmount(round4(gross));
        o.setCommission(round4(commission));
        o.setStampTax(round4(stampTax));
        o.setPriceSource(priceSource);
        o.setSignalDate(null);
        o.setExitDate(p.exitDate);
        o.setEventTime("CLOSE");
        return o;
    }

    private PortfolioOrder rejectBuy(String runId, int seq, LocalDate day, PendingBuy b, String reason) {
        PortfolioOrder o = baseOrder(runId, seq, "BUY", day, b.stockCode, b.exchangeType, b.stockName);
        o.setStatus("REJECTED");
        o.setRejectReason(reason);
        o.setSignalDate(b.signalDate);
        return o;
    }

    private PortfolioOrder rejectOrder(String runId, int seq, LocalDate day, Position p, String reason) {
        PortfolioOrder o = baseOrder(runId, seq, "SELL", day, p.stockCode, p.exchangeType, p.stockName);
        o.setStatus("REJECTED");
        o.setRejectReason(reason);
        o.setExitDate(p.exitDate);
        return o;
    }

    private Map<String, Object> orderMap(PortfolioOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", o.getSeq());
        m.put("action", o.getAction());
        m.put("trade_date", o.getTradeDate().toString());
        m.put("stock_code", o.getStockCode());
        m.put("exchange_type", o.getExchangeType());
        m.put("stock_name", o.getStockName());
        m.put("status", o.getStatus());
        m.put("reject_reason", o.getRejectReason());
        m.put("price", o.getPrice());
        m.put("shares", o.getShares());
        m.put("amount", o.getAmount());
        m.put("gross_amount", o.getGrossAmount());
        m.put("commission", o.getCommission());
        m.put("stamp_tax", o.getStampTax());
        m.put("price_source", o.getPriceSource());
        m.put("signal_date", o.getSignalDate() != null ? o.getSignalDate().toString() : null);
        m.put("exit_date", o.getExitDate() != null ? o.getExitDate().toString() : null);
        m.put("event_time", o.getEventTime());
        return m;
    }

    private Map<String, Object> navMap(PortfolioNavPoint np) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trade_date", np.getTradeDate().toString());
        m.put("cash", np.getCash());
        m.put("position_value", np.getPositionValue());
        m.put("nav", np.getNav());
        m.put("positions", np.getPositions());
        return m;
    }

    private Map<String, Object> positionMap(PortfolioPosition pos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stock_code", pos.getStockCode());
        m.put("exchange_type", pos.getExchangeType());
        m.put("stock_name", pos.getStockName());
        m.put("shares", pos.getShares());
        m.put("entry_price", pos.getEntryPrice());
        m.put("mark_price", pos.getMarkPrice());
        m.put("entry_date", pos.getEntryDate() != null ? pos.getEntryDate().toString() : null);
        m.put("exit_date", pos.getExitDate() != null ? pos.getExitDate().toString() : null);
        m.put("final_status", pos.getFinalStatus());
        m.put("unrealized_pnl", pos.getUnrealizedPnl());
        m.put("price_source", pos.getPriceSource());
        return m;
    }

    private Map<String, Object> runSummaryMap(PortfolioBacktestRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", r.getRunId());
        m.put("strategy_type", r.getStrategyType());
        m.put("prediction_run_id", r.getPredictionRunId());
        m.put("track", r.getTrack());
        m.put("start_date", r.getStartDate() != null ? r.getStartDate().toString() : null);
        m.put("end_date", r.getEndDate() != null ? r.getEndDate().toString() : null);
        m.put("horizon", r.getHorizon());
        m.put("final_nav", r.getFinalNav());
        m.put("total_return", r.getTotalReturn());
        m.put("max_drawdown", r.getMaxDrawdown());
        m.put("total_trades", r.getTotalTrades());
        m.put("reject_count", r.getRejectCount());
        m.put("status", r.getStatus());
        m.put("progress", r.getProgress() != null ? r.getProgress() : 0);
        m.put("error_message", r.getErrorMessage());
        m.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        m.put("updated_at", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
        return m;
    }

    // ========================================================================
    // 工具
    // ========================================================================

    private static LocalDate nextTradingDay(List<LocalDate> days, LocalDate day) {
        for (LocalDate d : days) {
            if (d.isAfter(day)) {
                return d;
            }
        }
        return null;
    }

    private static LocalDate tradingDayOffset(List<LocalDate> days, LocalDate from, int offset) {
        if (from == null) {
            return null;
        }
        int idx = days.indexOf(from);
        if (idx < 0) {
            for (int i = 0; i < days.size(); i++) {
                if (!days.get(i).isBefore(from)) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            return null;
        }
        int target = idx + offset;
        if (target >= days.size()) {
            return null;
        }
        return days.get(target);
    }

    /**
     * 成交执行价解析（MP-005 P0 修复）：仅接受 raw 价（market_price_raw），
     * 缺失即不可成交。严禁回退到 qfq 复权价——否则回测成交价与真实成交
     * 价不一致（前复权因子会系统性扭曲收益）。qfq 仅可用于 NAV 标记价。
     */
    private static PriceBar resolveExecutionBar(
            String code, String exchange, LocalDate date,
            Map<String, MarketPriceRaw> rawMap
    ) {
        String key = priceKey(code, exchange, date);
        MarketPriceRaw raw = rawMap.get(key);
        if (raw != null && (raw.getOpenPrice() != null || raw.getClosePrice() != null)) {
            return new PriceBar(
                    raw.getOpenPrice() != null ? raw.getOpenPrice().doubleValue() : null,
                    raw.getClosePrice() != null ? raw.getClosePrice().doubleValue() : null,
                    raw.getPreClose() != null ? raw.getPreClose().doubleValue() : null,
                    raw.getVolume() != null && raw.getVolume() <= 0,
                    "market_price_raw");
        }
        return null;
    }

    private static PriceBar resolvePriceBar(
            String code, String exchange, LocalDate date,
            Map<String, MarketPriceRaw> rawMap, Map<String, MarketData> qfqMap
    ) {
        String key = priceKey(code, exchange, date);
        MarketPriceRaw raw = rawMap.get(key);
        if (raw != null && (raw.getOpenPrice() != null || raw.getClosePrice() != null)) {
            return new PriceBar(
                    raw.getOpenPrice() != null ? raw.getOpenPrice().doubleValue() : null,
                    raw.getClosePrice() != null ? raw.getClosePrice().doubleValue() : null,
                    raw.getPreClose() != null ? raw.getPreClose().doubleValue() : null,
                    raw.getVolume() != null && raw.getVolume() <= 0,
                    "market_price_raw");
        }
        MarketData md = qfqMap.get(key);
        if (md != null) {
            return new PriceBar(
                    md.getOpenPrice() != null ? md.getOpenPrice().doubleValue() : null,
                    md.getClosePrice() != null ? md.getClosePrice().doubleValue() : null,
                    md.getPreClose() != null ? md.getPreClose().doubleValue() : null,
                    md.getVolume() != null && md.getVolume() <= 0,
                    "qfq_legacy");
        }
        return null;
    }

    private static double resolvePreClose(
            PriceBar bar, String code, String exchange, LocalDate date,
            Map<String, MarketPriceRaw> rawMap, Map<String, MarketData> qfqMap,
            List<LocalDate> tradingDays
    ) {
        if (bar.preClose != null && bar.preClose > 0) {
            return bar.preClose;
        }
        LocalDate prev = null;
        for (LocalDate d : tradingDays) {
            if (d.isBefore(date)) {
                prev = d;
            } else {
                break;
            }
        }
        if (prev != null) {
            PriceBar prevBar = resolvePriceBar(code, exchange, prev, rawMap, qfqMap);
            if (prevBar != null && prevBar.close != null) {
                return prevBar.close;
            }
        }
        return 0;
    }

    private static String priceKey(String code, String exchange, LocalDate date) {
        return code + "|" + exchange + "|" + date;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // V2-MP-030: 禁止序列化失败静默写 [] 空产物；fail-closed
            throw new IllegalStateException("组合回测 payload 序列化失败", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // V2-MP-030: 禁止损坏 JSON 静默降级为空 map 并标成功；fail-closed
            throw new IllegalStateException("组合回测结果 JSON 损坏，禁止静默降级", e);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // V2-MP-030: 禁止损坏 JSON 静默降级为空 map 并标成功；fail-closed
            throw new IllegalStateException("组合回测结果 JSON 损坏，禁止静默降级", e);
        }
    }

    private static LocalDate parseDate(Object raw, String field) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BusinessException(field + " 必填");
        }
        return LocalDate.parse(String.valueOf(raw).trim());
    }

    private static String strNullable(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        return String.valueOf(raw).trim();
    }

    private static String str(Object raw, String fallback) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return fallback;
        }
        return String.valueOf(raw).trim();
    }

    private static int intVal(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleVal(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 当日开盘买入的「等权但动态」预算追踪器。
     * 每笔预算 = 剩余现金 / 剩余待买数；当某笔被拒(未花钱)或买得便宜时，剩余现金相对变大，
     * 后续标的可用预算随之增加。修复固定 {@code budgetEach=cash/n} 会误拒高价标的的问题：
     * 旧逻辑下一旦前几笔买得便宜，后面标的仍按最初均摊额度被 {@code INSUFFICIENT_CASH_FOR_LOT} 误拒，
     * 即便实际剩余现金足够。
     */
    static final class DayBudgetGuard {
        private double remainingCash;
        private int remaining;

        DayBudgetGuard(double cash, int n) {
            this.remainingCash = cash;
            this.remaining = n;
        }

        /** 当前实际可用现金（与账户现金同步扣减）。 */
        double remainingCash() {
            return remainingCash;
        }

        /** 剩余待买数量。 */
        int remaining() {
            return remaining;
        }

        /** 下一笔的预算上限；剩余为 0 时兜底返回 0（不会发生，循环次数=初始 n）。 */
        double budgetForNext() {
            return remaining > 0 ? remainingCash / remaining : 0;
        }

        /** 本笔被拒而未花钱：仅减少待买数，现金保留，让渡给后续标的。 */
        void onRejected() {
            remaining--;
        }

        /** 本笔成交：同时减少现金与待买数。 */
        void onSpent(double totalCost) {
            remainingCash -= totalCost;
            remaining--;
        }
    }

    /**
     * 单笔买入决策。将「预算内最大可买手数」与「实际现金上限」两层判断集中到一处，
     * 便于回归测试固定预算误拒高价标的问题。返回 {@link BuyDecision} 作为成交或拒绝原因。
     */
    static BuyDecision decideBuy(double entryPx, double commissionRate, double budgetEach, double availableCash) {
        int shares = largestAffordableLot(entryPx, commissionRate, budgetEach);
        if (shares < LOT_SIZE) {
            return BuyDecision.reject("INSUFFICIENT_CASH_FOR_LOT");
        }
        double gross = entryPx * shares;
        double commission = Math.max(gross * commissionRate, MIN_COMMISSION);
        double totalCost = gross + commission;
        if (totalCost > availableCash + 1e-6) {
            return BuyDecision.reject("INSUFFICIENT_CASH");
        }
        return BuyDecision.fill(shares, gross, commission, totalCost);
    }

    record BuyDecision(boolean isFill, String rejectReason, int shares, double gross, double commission, double totalCost) {
        static BuyDecision fill(int shares, double gross, double commission, double totalCost) {
            return new BuyDecision(true, null, shares, gross, commission, totalCost);
        }

        static BuyDecision reject(String reason) {
            return new BuyDecision(false, reason, 0, 0, 0, 0);
        }
    }

    /**
     * 在给定预算内按「含佣金总成本」计算最大可买整手（100 股/手）。
     * 每笔佣金不低于 {@link #MIN_COMMISSION} 元（MP-005 最低佣金）。
     */
    private static int largestAffordableLot(double entryPx, double commissionRate, double budget) {
        if (entryPx <= 0 || budget <= 0) {
            return 0;
        }
        double costPerShare = entryPx * (1 + commissionRate);
        if (costPerShare <= 0) {
            return 0;
        }
        int maxLots = (int) (budget / (costPerShare * LOT_SIZE));
        while (maxLots > 0) {
            int shares = maxLots * LOT_SIZE;
            double gross = entryPx * shares;
            double commission = Math.max(gross * commissionRate, MIN_COMMISSION);
            if (gross + commission <= budget + 1e-6) {
                return shares;
            }
            maxLots--;
        }
        return 0;
    }

    private record PriceBar(Double open, Double close, Double preClose, boolean zeroVolume, String source) {}

    private static final class Position {
        final String stockCode;
        final String exchangeType;
        final String stockName;
        final int shares;
        final double entryPrice;
        final LocalDate entryDate;
        final LocalDate exitDate;

        Position(String stockCode, String exchangeType, String stockName, int shares,
                 double entryPrice, LocalDate entryDate, LocalDate exitDate) {
            this.stockCode = stockCode;
            this.exchangeType = exchangeType;
            this.stockName = stockName;
            this.shares = shares;
            this.entryPrice = entryPrice;
            this.entryDate = entryDate;
            this.exitDate = exitDate;
        }
    }

    private record PendingBuy(
            String stockCode, String exchangeType, String stockName,
            LocalDate signalDate, LocalDate entryDate, LocalDate exitDate
    ) {
        String key() {
            return stockCode + "|" + exchangeType;
        }
    }
}
