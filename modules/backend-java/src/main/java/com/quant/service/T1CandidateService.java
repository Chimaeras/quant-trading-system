package com.quant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.T1Candidate;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.ModelVersionRepository;
import com.quant.repository.T1CandidateRepository;
import com.quant.research.ResearchConvention;
import com.quant.research.CalibratorService;
import com.quant.research.LogisticModelTrainer;
import com.quant.research.artifact.ArtifactManifestValidator;
import com.quant.research.artifact.ModelDeploymentContract;
import com.quant.entity.Calibrator;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;
import com.quant.service.scoring.LimitUpEcologyService;
import com.quant.service.scoring.ScoringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * T+1 次日候选核心闭环（用户工作流）：
 * <ol>
 *   <li>T 日收盘后，对当日可交易股票池打分，生成「次日上涨候选 TopN」；</li>
 *   <li>T+1 结束后回填每只候选的次日表现（收盘涨幅/高开/涨停），聚合命中率；</li>
 *   <li>候选与核销结果落库（{@code t1_candidate}），支持按日回溯。</li>
 * </ol>
 *
 * <p>打分：短期动量（近 5 日涨幅）+ 量能（量比）+ 趋势位置（相对 MA20），输出为
 * <b>rule_score（0-50，截面排序用，非概率）</b>；候选 JSON 标注 score_type 以区别于概率。
 *
 * <p><b>为什么可行域是 0-50 而不是 0-100（D-T1-01，2026-10-13 修正）</b>：打分池在打分前已由
 * {@link ScoringUtils#isTradableForScoring} 以 {@code excludeLimitUpAtClose=true} 过掉「收盘涨停」的标的
 * （见 {@link #generateCandidates} 的预过滤与 {@link ScoringUtils} 的涨停闸），因此池内每只标的的
 * {@code is_limit_up} 恒为 false、{@code lianban_days} 恒为 0。此前公式里的 {@code +30 涨停} 与
 * {@code +min(15, 连板*5)} 两项对本打分池<b>恒不贡献任何分数</b>（对池内所有标的的影响完全相同，
 * 因此对排序也完全没有影响），而旧阈值 {@code 40} 是按「含涨停基因的 0-100 分布」设置的。
 * 结果是可实现的最高分只有约 35~49，实测 1,404~1,747 只通过流动性闸的标的中达到 40 分的为 0~1 只，
 * 候选清单长期恒空。本次修正：删除这两个不可达项，并把阈值按真实可达分布重新标定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class T1CandidateService {

    private static final int DEFAULT_TOP_N = 10;
    /**
     * 回看窗口（自然日）。需保证窗口内交易日数 ≥ {@code MIN_BARS}（30）：
     * 30 个交易日约需 42 个自然日，叠加春节/国庆等假期后最坏约需 52 个自然日，
     * 取 60 留足余量，避免生产环境因窗口过短而静默跳过大部分标的。
     */
    private static final int LOOKBACK_DAYS = 60;
    private static final int MIN_BARS = 30;
    private static final double MIN_AVG_AMOUNT = ScoringUtils.DEFAULT_MIN_AVG_AMOUNT;
    private static final int MIN_LISTING_DAYS = 60;

    // ===== 入选阈值门禁（V2-MP-027 P1）：TopN 只是上限，不是配额 =====
    /** 门禁版本标识：阈值由冻结的实验/配置版本产生，随 snapshot 落库。 */
    private static final String DEFAULT_ELIGIBILITY_THRESHOLD_VERSION = "t1_rule_v3";
    /**
     * rule_score 入选下限兜底值（可行域 0-50，截面排序分，非概率）。弱信号日宁可无标的也不凑数。
     *
     * <p><b>标定依据（D-T1-07，2026-10-14 重标定）</b>：旧值 35.0 的标定注释声称「命中 0/0/2/1/1」，
     * 但实测 2026-08-04..2026-10-13 共 45 个信号日、逐日复现生产打分池后为
     * <b>日均 3.4 只、4 日零候选</b>；落库快照更显示 11 次生成中 10 次为
     * {@code NO_ELIGIBLE_CANDIDATE}（2026-09-07 与 2026-10-12 的 1,265~1,340 只候选
     * <b>全部</b>低于 35）——即该阈值坐在可达分布的上尾极值端，候选清单长期恒空。
     *
     * <p>可用实证数据由 {@link #scoreT1} 的可达分布 + 次日收益联合标定：
     * <ul>
     *   <li>覆盖度：阈值 35 → 日均 3.4 只 / 8.9% 零候选日；30 → 8.6 只 / 2.2%；25 → 23.8 只 / 0%。</li>
     *   <li>预测力（326,783 样本，次日开盘买→次日收盘）：[25,30) +0.562%、
     *       <b>[30,35) +1.016%</b>、[35,50] +1.179%，全市场基准 +0.181%。</li>
     * </ul>
     * 取 30.0：预测力在 [30,35) 相对 [25,30) 抬升约 1.8 倍，同时零候选日降至 2.2%。
     *
     * <p><b>该阈值必须与 {@link #scoreT1} 的公式一起变更</b>，单独调阈值不构成修复。
     * 生产可用 {@code quant.t1.eligibility.rule-score-threshold} 覆盖。
     */
    private static final double DEFAULT_RULE_SCORE_THRESHOLD = 30.0;
    /**
     * 为什么「排除收盘涨停」不会让高分候选彻底消失（D-T1-07 澄清）：
     * 曾怀疑预过滤与打分构成闭环——即「能达标的票恰好都是涨停票，故全被删掉」。
     * 实测该闭环<b>不成立</b>：45 个信号日在排除涨停后，≥30 分的非涨停标的日均仍有 8.6 只、
     * 仅 1 日为零。真正的原因是旧阈值 35 落在分布上尾（p99.5 以上），而非预过滤过度删除。
     * 故本次修复只需重标定阈值；预过滤保持 {@code excludeLimitUpAtClose=true} 不变
     * （收盘涨停股次日大概率一字板，本就买不进，纳入候选反而误导）。
     * 为便于持续验证该判断，另输出 {@code limit_up_excluded} 诊断计数。
     */
    public static final double RULE_SCORE_THRESHOLD_FLOOR = 15.0;
    /**
     * rule_score 理论最高分 = 动量 20 + 量能 15 + 趋势 15（三项上限之和）。
     * 与 {@link #scoreT1} 的分量上限必须保持一致；改动公式上限时同步改这里。
     */
    private static final double RULE_SCORE_MAX = 50.0;
    /** 概率轨入选下限：仅当加载到 T+1 champion 概率模型时启用（绝对概率，非截面 percentile）。 */
    private static final double PROBABILITY_THRESHOLD = 0.55;

    /**
     * 入选阈值（可由 {@code quant.t1.eligibility.rule-score-threshold} 覆盖）。
     * 非 static：配置注入 + 单元测试可按场景覆写，避免把标定值硬编码进不可替换的常量。
     */
    @org.springframework.beans.factory.annotation.Value(
            "${quant.t1.eligibility.rule-score-threshold:" + DEFAULT_RULE_SCORE_THRESHOLD + "}")
    private double ruleScoreThreshold = DEFAULT_RULE_SCORE_THRESHOLD;

    /** 门禁版本标识（可由 {@code quant.t1.eligibility.threshold-version} 覆盖），随快照落库供回溯。 */
    @org.springframework.beans.factory.annotation.Value(
            "${quant.t1.eligibility.threshold-version:" + DEFAULT_ELIGIBILITY_THRESHOLD_VERSION + "}")
    private String eligibilityThresholdVersion = DEFAULT_ELIGIBILITY_THRESHOLD_VERSION;

    private final MarketDataRepository marketDataRepository;
    private final PointInTimeUniverseProvider pointInTimeUniverseProvider;
    private final T1CandidateRepository t1CandidateRepository;
    private final LimitUpEcologyService limitUpEcologyService;
    private final TradingCalendarService tradingCalendarService;
    private final com.quant.repository.ModelVersionRepository modelVersionRepository;
    private final com.quant.repository.IndexQuoteRepository indexQuoteRepository;
    private final ObjectMapper objectMapper;
    private final CalibratorService calibratorService;
    private java.time.Clock clock = java.time.Clock.system(java.time.ZoneId.of("Asia/Shanghai"));
    private static final String FEATURE_SET_VERSION = "t1_rule_score_v2";
    private static final double MIN_DAILY_COVERAGE = 0.95;
    /**
     * 覆盖率闸分母的「活跃窗口」（自然日）。分母只统计在该窗口内真实有行情的在市标的。
     *
     * <p><b>取值依据（D-T1-06 标定）</b>：在 2026-01-05..2026-08-31 共 155 个交易日上实测，
     * 阈值保持 0.95 不变时的通过情况：
     * <ul>
     *   <li>W=60：min=0.9596、median=0.9958，155/155 通过；</li>
     *   <li>W=120：min=0.9475，130/155 通过（仍会误阻断）；</li>
     *   <li>W=180：96/155；W=250：47/155。</li>
     * </ul>
     * 选 W=60（≈3 个交易月）：既能剔除 284 个北交所代码迁移遗留的双 exchange_type 键与
     * 长期休眠标的，又保留对真实断崖的检出（2026-09-08 起 coverage=0.0455 → 正确 BLOCKED）。
     * <p><b>该值属待验证假设</b>：标定样本为单一数据快照的 155 个交易日，尚未经样本外验证；
     * 修改此值等于改变闸门灵敏度，须重新走标定流程并留痕。
     */
    private static final int ACTIVE_WINDOW_DAYS = 60;

    /** 生成 T 日次日候选清单并落库。 */
    @Transactional
    public Map<String, Object> generateCandidates(LocalDate tradeDate, int topN) {
        int n = Math.max(1, Math.min(topN > 0 ? topN : DEFAULT_TOP_N, 50));
        if (!dailyBarClosed(tradeDate)) {
            return emptyDecision(tradeDate, "DAILY_BAR_NOT_CLOSED", "该日尚未收盘，暂不生成日频候选",
                    calendarKnowsTradingDay(tradeDate));
        }
        // 已保存的预测是历史证据。重复请求只读取，不以修订后的行情或参数覆盖它。
        if (t1CandidateRepository.findByTradeDate(tradeDate).isPresent()) {
            Map<String, Object> saved = getCandidates(tradeDate);
            saved.put("snapshot_reused", true);
            saved.put("requested_top_n", n);
            return saved;
        }
        LocalDate rangeStart = tradeDate.minusDays(LOOKBACK_DAYS);
        // 生成时刻：T1Candidate.createdAt 是 @Column(updatable=false)，仅记录首次落库时间，
        // 重新生成不会更新它，因此单靠 createdAt 无法回答「这份清单是什么时候算的」。这里显式记录。
        java.time.LocalDateTime generatedAt = java.time.LocalDateTime.now(clock);
        PointInTimeUniverseProvider.UniverseView universe = pointInTimeUniverseProvider.loadAsOf(tradeDate);

        // 区间行情（单次批量），按股票分组
        Map<String, List<MarketData>> byStock = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate)
                .stream()
                .collect(Collectors.groupingBy(
                        m -> m.getStockCode() + "|" + m.getExchangeType(), LinkedHashMap::new, Collectors.toList()));
        Map<String, MarketData> todayByKey = marketDataRepository.findByTradeDate(tradeDate).stream()
                .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));

        // P0-3（2026-10-13 修正）：数据日恒等于请求日，不存在任何回退路径；
        // 因此不再输出恒为 false 的 `data_date_fallback`（详见 emptyDecision 注释）。
        LocalDate actualDate = tradeDate;
        if (todayByKey.isEmpty()) {
            // P0-3（2026-10-13）：原先无论何种原因都返回 NO_DATA，使用者无法区分
            // 「那天根本不是交易日」与「那天是交易日但数据缺失/过期」。
            // 现在借助交易日历把语义**分开**（不放宽、不回退、不落库）：
            //   · 日历覆盖该日且判定为交易日  → NO_DATA（确定是数据缺失）
            //   · 日历覆盖该日但判定为非交易日 → NOT_A_TRADING_DAY（确定是休市）
            //   · 该日不在日历覆盖区间内      → UNKNOWN（不得妄断，提示补齐日历）
            // 第三种态是必需的：本地实测日历仅到 2026-08-19，而行情到 2026-10-12，
            // 若把区间外一律当作休市，会把 2026-09/10 的真实数据断崖说成「非交易日」。
            if (calendarKnowsTradingDay(tradeDate)) {
                return emptyDecision(tradeDate, "NO_DATA",
                        "所选信号日是交易日但主表无行情，暂不判断；请检查行情入库是否滞后或缺失",
                        true);
            }
            if (calendarCoversDay(tradeDate)) {
                return emptyDecision(tradeDate, "NOT_A_TRADING_DAY",
                        "所选信号日经交易日历确认非交易日（休市），不生成日频候选", false);
            }
            return emptyDecision(tradeDate, "UNKNOWN",
                    "所选信号日不在交易日历覆盖范围内，且主表无行情；"
                            + "无法区分「非交易日」与「行情缺失」，请先补齐交易日历再判断",
                    false);
        }

        Map<String, StockInfo> infoByKey = universe.byKey();
        // ===== 覆盖率闸（D-T1-06，2026-10-13 修正分母） =====
        // 原实现的分母 = `stock_info` 中 status=1 且 list_date<=T 的**全部键**，与「当日真实
        // 应有行情的键」不是同一个集合，导致闸门恒不可通过。实测（本地 quant_db，只读）：
        //   · 分母 5,835；门槛 0.95 需 5,544 根当日 bar；
        //   · 历史单日最大 bar 数仅 5,477（2026-03..2026-08 正常期 mean 5,463.6）；
        //   · 最大可达覆盖率 0.9386 < 0.95，缺口恒为 67 根 → 每个信号日都返回
        //     INCOMPLETE_DAILY_DATA，候选恒空，D-T1-01/D-T1-02 的阈值修复从未真正生效。
        // 分母被放大的构成（实测，2026-10-13 第 7 轮修正归因）：「分母键」与「当日真有 bar 的键」
        // 相差约 358 个键，其成因**不止一种**，此前注释只归因于北交所代码迁移，归因不完整：
        //   · 284 个 stock_code 同时存在 BSE(920xxx) 与 SSE 两个 exchange_type 行（迁移遗留），
        //     两个键都有 bar —— 这一部分**不构成**缺口（两个键都算covered）；
        //   · 其余约 358 个键是「在主表中但于 [T-60d, T] 内没有任何 bar」的键，
        //     多为长期停牌/已实质退市/陈旧编码，纯生命周期条件（status/list_date）无法剔除。
        // 本修正用回看窗口内是否真有 bar 来剔除后者，与前者无关。

        // 修正：分母改为「**回看窗口内真的在交易**的键」——即该键在 [T-ACTIVE_WINDOW_DAYS, T]
        // 内至少有一根 bar。这同时剔除陈旧编码与长期休眠/已退市标的，且不使用 >T 的任何数据
        // （无前视）。阈值 MIN_DAILY_COVERAGE 保持 0.95 **不变**，未放宽任何可信度保护。
        // 标定证据：2026-01-05..2026-08-31 共 155 个交易日，该口径下覆盖率 min=0.9596、
        // median=0.9958，155/155 通过；同时仍能正确阻断真实数据断崖
        // （2026-09-08 起 bar 数由 5,456 掉到 249 → coverage 0.0455 → BLOCKED）。
        Map<String, Boolean> activeRecently = new HashMap<>();
        for (Object[] pair : marketDataRepository.findDistinctStockPairsInRange(
                tradeDate.minusDays(ACTIVE_WINDOW_DAYS), tradeDate)) {
            activeRecently.put(pair[0] + "|" + pair[1], Boolean.TRUE);
        }
        long expected = infoByKey.entrySet().stream().filter(e -> Integer.valueOf(1).equals(e.getValue().getStatus())
                && e.getValue().getListDate() != null && !e.getValue().getListDate().isAfter(tradeDate)
                && activeRecently.containsKey(e.getKey())).count();
        long covered = infoByKey.entrySet().stream().filter(e -> Integer.valueOf(1).equals(e.getValue().getStatus())
                && e.getValue().getListDate() != null && !e.getValue().getListDate().isAfter(tradeDate)
                && activeRecently.containsKey(e.getKey())
                && validDailyBar(todayByKey.get(e.getKey()))).count();
        double dailyCoverage = expected > 0 ? covered / (double) expected : 0;
        if (dailyCoverage < MIN_DAILY_COVERAGE) {
            Map<String, Object> empty = emptyDecision(tradeDate, "INCOMPLETE_DAILY_DATA",
                    "当日行情或证券主数据覆盖不足，暂不判断（分母=信号日回看 "
                            + ACTIVE_WINDOW_DAYS + " 个自然日内真实有行情的在市标的）",
                    calendarKnowsTradingDay(tradeDate));
            empty.putAll(universe.lineage());
            empty.put("daily_coverage", dailyCoverage);
            empty.put("daily_expected", expected);
            empty.put("daily_covered", covered);
            empty.put("coverage_denominator_rule",
                    "该键在 [T-" + ACTIVE_WINDOW_DAYS + "d, T] 内有 bar 且 status=1 且 list_date<=T");
            return empty;
        }

        // 涨停股次日溢价历史分布（用于次日可买性/高开追高风险预判）
        // 注意：生成阶段只能用 ≤ T-1 的历史数据，不能读 T+1（否则未来函数）。
        // historicalNextDayPremium 只统计信号日 ≤ T-2 的涨停事件，数据截止 T-1。
        Map<String, Object> premium = limitUpEcologyService.historicalNextDayPremium(actualDate, 50);

        // 尝试加载 horizon=1 的 champion 概率模型；无匹配 champion 则仅输出规则分
        T1ProbLoadResult modelLoad = universe.pitLimited()
                ? new T1ProbLoadResult(null, "PIT_LIMITED") : loadT1ProbModelWithReason(actualDate);
        T1ProbModel probModel = modelLoad.model();
        String fallbackReason = modelLoad.fallbackReason();

        List<Map<String, Object>> scored = new ArrayList<>();
        // D-T1-07 诊断：统计「仅因收盘涨停而被预过滤剔除」的标的，以及其中最高分。
        // 这是持续验证「涨停闭环不成立」这一判断的证据——若某日 limit_up_excluded 很大
        // 且其 max 分远高于阈值，说明高分标的确实集中在涨停股上，需要重新审视预过滤策略。
        int limitUpExcludedCount = 0;
        double limitUpExcludedMaxScore = 0.0;
        for (Map.Entry<String, MarketData> e : todayByKey.entrySet()) {
            String key = e.getKey();
            MarketData today = e.getValue();
            List<MarketData> stockBars = byStock.getOrDefault(key, List.of());
            if (stockBars.size() < MIN_BARS) {
                continue;
            }
            String code = today.getStockCode();
            String exchange = today.getExchangeType();
            StockInfo info = infoByKey.get(key);
            if (info == null || !Integer.valueOf(1).equals(info.getStatus()) || info.getListDate() == null) {
                continue;
            }
            String name = info.getStockName();
            if (name == null || name.isBlank() || stockBars.stream().anyMatch(b -> !validDailyBar(b))) {
                continue;
            }
            if (!ScoringUtils.isTradableForScoring(stockBars, code, exchange, name,
                    info != null ? info.getStatus() : null, true, true, true)) {
                // 区分「因收盘涨停被剔除」与其他原因（停牌/ST/零成交等）：
                // 用同一组参数关掉涨停闸重判，仍通过则说明唯一拒绝原因就是收盘涨停。
                // 此时额外算出它的 rule_score 作为对照诊断（不进入候选池）。
                if (ScoringUtils.isTradableForScoring(stockBars, code, exchange, name,
                        info.getStatus(), true, false, true)
                        && ScoringUtils.passesMinAvgAmount(stockBars, MIN_AVG_AMOUNT)
                        && ScoringUtils.hasMinListingTenure(info.getListDate(), actualDate,
                                MIN_LISTING_DAYS, stockBars)) {
                    Map<String, Object> luEx = limitUpEcologyService.stockLimitUpFeatures(stockBars, today, name);
                    double scoreEx = scoreT1(luEx, shortIndicators(stockBars));
                    limitUpExcludedCount++;
                    if (scoreEx > limitUpExcludedMaxScore) {
                        limitUpExcludedMaxScore = scoreEx;
                    }
                }
                continue;
            }
            if (!ScoringUtils.passesMinAvgAmount(stockBars, MIN_AVG_AMOUNT)) {
                continue;
            }
            if (!ScoringUtils.hasMinListingTenure(info != null ? info.getListDate() : null,
                    actualDate, MIN_LISTING_DAYS, stockBars)) {
                continue;
            }

            Map<String, Object> lu = limitUpEcologyService.stockLimitUpFeatures(stockBars, today, name);
            double[] ind = shortIndicators(stockBars);
            // 注意：池内标的已在上方预过滤时排除「收盘涨停」，故 lu 的 is_limit_up/lianban_days
            // 不能作为加分项（会对全池一致，既无区分度又使可行域虚高）；仅用于解释与可买性预判。
            double ruleScore = scoreT1(lu, ind);

            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("stock_code", code);
            cand.put("exchange_type", exchange);
            cand.put("stock_name", name != null ? name : code);
            cand.put("rule_score", round4(ruleScore));
            // 仅 champion + schema 匹配时输出概率；否则只保留规则分，probability 字段不写
            double rankScore = ruleScore;
            String scoreType = "RULE_SCORE";
            boolean isProb = false;
            if (probModel != null) {
                double prob = probModel.predictProbability(calibratorService, ruleScore);
                if (!Double.isFinite(prob) || prob < 0 || prob > 1) {
                    throw new IllegalStateException("校准概率不在 [0,1] 内，拒绝生成候选");
                }
                rankScore = prob;
                scoreType = "CALIBRATED_PROBABILITY";
                isProb = true;
                cand.put("probability", round4(prob));
            }
            cand.put("score", round4(rankScore));
            cand.put("score_type", scoreType);
            cand.put("is_probability", isProb);
            cand.put("close", today.getClosePrice() != null ? today.getClosePrice().doubleValue() : null);
            cand.put("change_pct", today.getChangePct() != null ? today.getChangePct().doubleValue() : 0);
            cand.put("is_limit_up", lu.getOrDefault("is_limit_up", false));
            cand.put("lianban_days", lu.getOrDefault("lianban_days", 0));
            cand.put("volume_ratio", lu.getOrDefault("volume_ratio", 0));
            cand.put("turnover_rate", lu.getOrDefault("turnover_rate", 0));
            cand.put("ret1", round4(ind[0]));
            cand.put("ret5", round4(ind[1]));
            cand.put("ma_pos", round4(ind[2]));
            cand.put("reasons", buildReasons(lu, ind));

            // 次日可买性/风险预判（收盘后即可用，非等次日核销）
            Map<String, Object> preTrade = buildPreTrade(lu, premium);
            cand.put("pre_trade", preTrade);
            cand.put("buyability", preTrade.get("buyability"));
            cand.put("risk_tags", preTrade.get("risk_tags"));
            scored.add(cand);
        }

        // ===== 入选阈值门禁（V2-MP-027）：先过滤 eligible，再按 score 排序截断到 topN =====
        // TopN 只是上限（cap），不是配额（quota）。弱信号日允许返回 0 条并明确标注。
        Map<String, Object> eligibility = applyEligibilityGate(scored, probModel, n);
        List<Map<String, Object>> top = new ArrayList<>((List<Map<String, Object>>) eligibility.get("top"));
        Map<String, Integer> rejectedByReason = (Map<String, Integer>) eligibility.get("rejectedByReason");
        int eligibleCount = (Integer) eligibility.get("eligibleCount");

        T1Candidate rec = new T1Candidate();
        rec.setTradeDate(actualDate);
        rec.setTopN(n);
        rec.setCandidatesJson(toJson(top));
        rec.setSettled(Boolean.FALSE);
        rec.setSettledJson(null);
        // 阈值门禁元数据随生态快照落库（避免 DB 迁移），供回溯/核销时溯源
        Map<String, Object> ecology = new LinkedHashMap<>(limitUpEcologyService.ecology(actualDate, 30));
        Map<String, Object> eligMeta = new LinkedHashMap<>();
        eligMeta.put("threshold_version", eligibilityThresholdVersion);
        eligMeta.put("rule_score_threshold", effectiveRuleScoreThreshold());
        eligMeta.put("rule_score_max", RULE_SCORE_MAX);
        eligMeta.put("probability_threshold", probModel != null ? PROBABILITY_THRESHOLD : null);
        eligMeta.put("eligible_count", eligibleCount);
        eligMeta.put("returned_count", top.size());
        eligMeta.put("total_scored", scored.size());
        eligMeta.put("rejected_by_reason", rejectedByReason);
        // D-T1-07：max_rule_score 必须落库。此前它只存在于生成响应（out），
        // 落库快照里缺失，导致按日回溯时无法判断空候选是「完全没信号」还是「就差一点」，
        // 也无法事后评估阈值是否定得过高——这正是旧阈值 35 长期未被发现的原因之一。
        eligMeta.put("max_rule_score", eligibility.get("maxRuleScore"));
        // 被预过滤剔除的收盘涨停股数量及其最高分（对照诊断，不进入候选池）
        eligMeta.put("limit_up_excluded", limitUpExcludedCount);
        eligMeta.put("limit_up_excluded_max_score", round4(limitUpExcludedMaxScore));
        eligMeta.put("status", top.isEmpty() ? "NO_ELIGIBLE_CANDIDATE" : "OK");
        // 可追溯性：落库侧同样记录生成时刻与实际数据日，避免只能靠响应体回溯
        eligMeta.put("generated_at", generatedAt.toString());
        eligMeta.put("requested_trade_date", tradeDate.toString());
        // P0-3（2026-10-13）：不再落库恒为 false 的 data_date_fallback（本服务无回退路径）。
        eligMeta.put("data_cutoff", actualDate.toString());
        eligMeta.put("candidate_invalidation", "仅用于下一交易日观察；下一交易日结束或数据质量异常时失效，候选不构成买卖指令");
        eligMeta.put("daily_coverage", dailyCoverage);
        eligMeta.put("daily_expected", expected);
        eligMeta.put("daily_covered", covered);
        eligMeta.put("quality_status", universe.pitLimited() ? "PIT_LIMITED" : "PASS");
        eligMeta.put("score_type", probModel != null ? "CALIBRATED_PROBABILITY" : "RULE_SCORE");
        eligMeta.put("score_model", probModel != null ? probModel.modelVersion() : null);
        eligMeta.put("calibrator_version", probModel != null ? probModel.calibrator().getCalibratorVersion() : null);
        eligMeta.put("probability_verified", probModel != null);
        eligMeta.put("fallback_reason", fallbackReason);
        // PIT 质量状态随快照落库：此前 pit_limited 只在生成响应里，getCandidates 与页面都拿不到
        eligMeta.putAll(universe.lineage());
        ecology.put("eligibility", eligMeta);
        rec.setEcologyJson(toJson(ecology));
        t1CandidateRepository.save(rec);

        boolean noEligible = top.isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        // ===== 可追溯性（D-T1-03 / P0-3）：声明数据日、口径版本与失效条件 =====
        // P0-3（2026-10-13 修正）：此处原先输出 `data_date_fallback = !actualDate.isEqual(tradeDate)`，
        // 而 `actualDate` 自始至终等于 `tradeDate`，故该字段**恒为 false**。
        // 更严重的是它暗示「存在回退能力」，但本服务从无回退路径——
        // 请求日无行情时走的是 emptyDecision 阻断，不是替换成别的数据日。
        // 现删除该字段；「非交易日」与「交易日但数据缺失」改由状态码与
        // `calendar_trading_day` 如实表达。
        out.put("trade_date", actualDate.toString());
        out.put("requested_trade_date", tradeDate.toString());
        out.put("data_cutoff", actualDate.toString());
        out.put("calendar_trading_day", calendarKnowsTradingDay(tradeDate));
        out.put("generated_at", generatedAt.toString());
        out.put("threshold_version", eligibilityThresholdVersion);
        out.put("rule_score_max", RULE_SCORE_MAX);
        out.putAll(eligMeta);
        out.put("probability_verified", probModel != null);
        out.put("top_n", top.size());
        out.put("eligible_count", eligibleCount);
        out.put("returned_count", top.size());
        out.put("total_scored", scored.size());
        out.put("max_rule_score", eligibility.get("maxRuleScore"));
        out.put("rejected_count", scored.size() - eligibleCount);
        out.put("rejected_by_reason", rejectedByReason);
        out.put("candidates", top);
        out.put("settled", false);
        out.put("status", noEligible ? "NO_ELIGIBLE_CANDIDATE" : "OK");
        if (probModel != null) {
            out.put("score_model", probModel.modelVersion());
            out.put("score_type", "CALIBRATED_PROBABILITY");
            out.put("fallback_reason", null);
            out.put("note", "T+1 次日候选：已加载 champion 概率模型 " + probModel.modelVersion()
                    + "，按校准概率排序（rule_score 见各候选字段）；入选需 rule_score≥"
                    + ruleScoreThreshold + " 且概率≥" + PROBABILITY_THRESHOLD
                    + (noEligible ? "；当日无合格标的（NO_ELIGIBLE_CANDIDATE），宁可空不凑数" : "")
                    + "；点击「次日核销」在 T+1 结束后回填表现");
        } else {
            out.put("score_type", "RULE_SCORE");
            out.put("score_model", null);
            out.put("fallback_reason", fallbackReason != null ? fallbackReason
                    : "NO_CHAMPION_MODEL");
            // D-T1-01：说明文字必须与打分口径一致。旧的「涨停基因+短期动量+量能+趋势」是错的：
            // 候选池在打分前已排除收盘涨停标的，涨停/连板因子在这条路径上恒不可达（已删除），
            // 涨停基因/连板只是生态快照（/research/t1/ecology），不参与候选排序。
            out.put("note", "T+1 次日候选：仅输出 RULE_SCORE（短期动量+量能+趋势，0-" + RULE_SCORE_MAX
                    + "），非概率；不含涨停基因（涨停标的已在打分前排除，连板仅作生态快照展示）；"
                    + "入选需 rule_score≥" + effectiveRuleScoreThreshold()
                    + "；当日最高 rule_score=" + (eligibility.get("maxRuleScore") == null ? "-"
                            : eligibility.get("maxRuleScore"))
                    + (noEligible ? "；当日无合格标的（NO_ELIGIBLE_CANDIDATE），宁可空不凑数" : "")
                    + "；回退原因：" + (fallbackReason != null ? fallbackReason : "NO_CHAMPION_MODEL")
                    + "。未发布 challenger/特征不匹配模型不会自动上线；"
                    + "点击「次日核销」在 T+1 结束后回填表现");
        }
        out.putAll(universe.lineage());
        return out;
    }

    /**
     * 入选阈值门禁（V2-MP-027）：先按硬性条件过滤 eligible，再按 score 排序并截断到 topN。
     *
     * <p>门槛：可交易/流动性/上市时长（打分前已过滤）+ rule_score≥{@link #ruleScoreThreshold}
     * +（若加载了概率模型）校准概率≥{@link #PROBABILITY_THRESHOLD}。被拒原因逐条计数，
     * 用于响应与快照中的 rejected_by_reason。TopN 只是上限：eligible 不足时原样返回，不补足。
     *
     * <p><b>截断必须真的执行（D-T1-02，2026-10-13 修正）</b>：此前本方法返回全部 eligible，
     * 调用方虽写入 {@code rec.setTopN(n)} 与 {@code top_n=top.size()}，却从未做 {@code subList}，
     * 因此 {@code ?topN=10} 可能返回远超 10 条。insertionCount 单独回传，便于区分
     * 「合格数」与「实际返回数」。
     */
    private Map<String, Object> applyEligibilityGate(List<Map<String, Object>> scored, T1ProbModel probModel,
                                                     int topN) {
        double threshold = effectiveRuleScoreThreshold();
        List<Map<String, Object>> eligible = new ArrayList<>();
        Map<String, Integer> rejectedByReason = new LinkedHashMap<>();
        for (Map<String, Object> c : scored) {
            double ruleScore = ((Number) c.get("rule_score")).doubleValue();
            if (ruleScore < threshold) {
                rejectedByReason.merge("BELOW_RULE_SCORE_THRESHOLD", 1, Integer::sum);
                continue;
            }
            if (probModel != null) {
                double prob = ((Number) c.get("probability")).doubleValue();
                if (prob < PROBABILITY_THRESHOLD) {
                    rejectedByReason.merge("BELOW_PROBABILITY_THRESHOLD", 1, Integer::sum);
                    continue;
                }
            }
            eligible.add(c);
        }
        eligible.sort(Comparator.comparingDouble((Map<String, Object> c) -> (double) c.get("score")).reversed());
        int eligibleCount = eligible.size();
        // 诊断：把「当日最高 rule_score」一并回传。没有它时，空候选只表现为
        // rejected_by_reason={BELOW_RULE_SCORE_THRESHOLD:n}，使用者无法判断
        // 「完全没有信号」还是「就差一点」，也无法评估阈值是否定得过高。
        double maxRuleScore = scored.stream()
                .mapToDouble(c -> ((Number) c.get("rule_score")).doubleValue())
                .max().orElse(0.0);
        // TopN 是上限不是配额：合格数不足时原样返回，超出时截断
        List<Map<String, Object>> top = eligibleCount > topN
                ? new ArrayList<>(eligible.subList(0, topN))
                : eligible;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("top", top);
        result.put("eligibleCount", eligibleCount);
        result.put("maxRuleScore", maxRuleScore);
        result.put("rejectedByReason", rejectedByReason);
        return result;
    }

    /**
     * 生效阈值：对配置值做边界收敛，避免运维误配把门禁关掉（=&gt;0）或配成不可达（&gt;满分）。
     *
     * <p>下限 {@link #RULE_SCORE_THRESHOLD_FLOOR}（15.0）：低于该值时打分池会几乎全量通过，
     * 「入选门禁」名存实亡；上限 {@link #RULE_SCORE_MAX}：超过满分即恒空候选。
     * 越界时记录告警并按边界值生效，不静默接受非法配置。
     */
    private double effectiveRuleScoreThreshold() {
        double configured = ruleScoreThreshold;
        if (!Double.isFinite(configured)) {
            log.warn("quant.t1.eligibility.rule-score-threshold 非法（{}），回退默认值 {}",
                    configured, DEFAULT_RULE_SCORE_THRESHOLD);
            return DEFAULT_RULE_SCORE_THRESHOLD;
        }
        if (configured < RULE_SCORE_THRESHOLD_FLOOR || configured > RULE_SCORE_MAX) {
            double clamped = Math.max(RULE_SCORE_THRESHOLD_FLOOR, Math.min(RULE_SCORE_MAX, configured));
            log.warn("quant.t1.eligibility.rule-score-threshold={} 超出合法区间 [{}, {}]，按 {} 生效",
                    configured, RULE_SCORE_THRESHOLD_FLOOR, RULE_SCORE_MAX, clamped);
            return clamped;
        }
        return configured;
    }

    /** T+1 结束后核销：回填候选次日表现并聚合命中率。 */
    @Transactional
    public Map<String, Object> settleCandidates(LocalDate tradeDate) {
        T1Candidate rec = t1CandidateRepository.findByTradeDate(tradeDate).orElse(null);
        if (rec == null) {
            return Map.of("status", "not_found", "message", "该日尚未生成候选，请先生成");
        }
        if (Boolean.TRUE.equals(rec.getSettled())) {
            Map<String, Object> saved = new LinkedHashMap<>(parseMap(rec.getSettledJson()));
            saved.put("trade_date", tradeDate.toString());
            saved.put("status", "ALREADY_SETTLED");
            saved.put("settled", true);
            return saved;
        }
        List<Map<String, Object>> candidates = parseList(rec.getCandidatesJson());
        if (candidates.isEmpty()) {
            return Map.of("status", "no_candidates", "trade_date", tradeDate.toString());
        }
        LocalDate nextDay = tradingCalendarService.findTradingDays(tradeDate, tradeDate.plusDays(7))
                .stream().filter(d -> !d.isEqual(tradeDate)).findFirst().orElse(null);
        if (nextDay == null) {
            return Map.of("status", "no_next_day", "message", "无次日交易日（数据未覆盖）");
        }
        if (!dailyBarClosed(nextDay)) {
            return Map.of("status", "NEXT_DAY_NOT_CLOSED", "trade_date", tradeDate.toString(),
                    "settled", false, "message", "下一交易日尚未收盘，暂不核销");
        }
        Map<String, MarketData> nextByKey = marketDataRepository.findByTradeDate(nextDay).stream()
                .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));

        List<Map<String, Object>> results = new ArrayList<>();
        int hitCount = 0;
        int highOpenCount = 0;
        double retSum = 0;
        double gapSum = 0;
        int valid = 0;
        for (Map<String, Object> cand : candidates) {
            String code = String.valueOf(cand.get("stock_code"));
            String exchange = String.valueOf(cand.get("exchange_type"));
            double closeT = cand.get("close") instanceof Number num ? num.doubleValue() : Double.NaN;
            MarketData next = nextByKey.get(code + "|" + exchange);
            Map<String, Object> r = new LinkedHashMap<>(cand);
            if (next == null || next.getClosePrice() == null || next.getOpenPrice() == null
                    || next.getClosePrice().signum() <= 0 || next.getOpenPrice().signum() <= 0
                    || !Double.isFinite(closeT) || closeT <= 0) {
                r.put("settled", false);
                r.put("skip", "no_next_quote");
            } else {
                double ret = next.getClosePrice().doubleValue() / closeT - 1;
                double gap = next.getOpenPrice().doubleValue() / closeT - 1;
                double highRet = (next.getHighPrice() != null && next.getHighPrice().doubleValue() > 0)
                        ? next.getHighPrice().doubleValue() / closeT - 1 : ret;
                double nextPreClose = next.getPreClose() != null && next.getPreClose().doubleValue() > 0
                        ? next.getPreClose().doubleValue() : closeT;
                double limitPct = TradingLimitRules.resolveLimitPct(code, exchange, String.valueOf(cand.get("stock_name")));
                boolean nextLimitUp = StrategyBacktestEngine.isLimitUpAtClose(next, nextPreClose, limitPct);
                boolean hit = ret > 0;
                r.put("next_ret", round4(ret));
                r.put("next_high_ret", round4(highRet));
                r.put("gap", round4(gap));
                r.put("next_close", next.getClosePrice().doubleValue());
                r.put("next_is_limit_up", nextLimitUp);
                r.put("hit", hit);
                r.put("settled", true);
                if (hit) hitCount++;
                if (gap > 0) highOpenCount++;
                retSum += ret;
                gapSum += gap;
                valid++;
            }
            results.add(r);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("top_n", results.size());
        summary.put("settled", valid);
        summary.put("hit_count", hitCount);
        summary.put("hit_rate", valid > 0 ? round4((double) hitCount / valid) : 0);
        summary.put("avg_next_ret", valid > 0 ? round4(retSum / valid) : 0);
        summary.put("avg_gap", valid > 0 ? round4(gapSum / valid) : 0);
        summary.put("high_open_count", highOpenCount);

        boolean complete = valid == candidates.size();
        summary.put("missing", candidates.size() - valid);
        rec.setSettled(complete);
        rec.setSettledJson(toJson(Map.of(
                "next_trade_date", nextDay.toString(),
                "quality_status", complete ? "COMPLETE" : "PARTIAL_DATA",
                "summary", summary,
                "results", results)));
        t1CandidateRepository.save(rec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("next_trade_date", nextDay.toString());
        out.put("summary", summary);
        out.put("results", results);
        out.put("settled", complete);
        out.put("status", complete ? "SETTLED" : "PARTIAL_DATA");
        out.put("note", "次日方向统计：hit=次日收盘>信号日收盘；未扣费用、滑点，不代表可成交收益；"
                + (complete ? "行情完整，核销结果已冻结" : "候选行情缺失，暂不完成核销或纳入累计统计"));
        return out;
    }

    /** 按日回溯候选（含核销结果）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getCandidates(LocalDate tradeDate) {
        T1Candidate rec = t1CandidateRepository.findByTradeDate(tradeDate).orElse(null);
        if (rec == null) {
            return Map.of("trade_date", tradeDate.toString(), "candidates", List.of(), "found", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", rec.getTradeDate().toString());
        out.put("top_n", rec.getTopN());
        out.put("candidates", parseList(rec.getCandidatesJson()));
        out.put("settled", Boolean.TRUE.equals(rec.getSettled()));
        if (rec.getSettledJson() != null) {
            out.put("settle", parseMap(rec.getSettledJson()));
        }
        if (rec.getEcologyJson() != null) {
            Map<String, Object> ecology = parseMap(rec.getEcologyJson());
            out.put("ecology", ecology);
            // 回放路径同样暴露口径与质量状态：此前这些字段只在生成响应里，
            // 按日回溯时看不到「用的哪版阈值/PIT 是否受限」，属于可追溯性缺口（D-T1-03）。
            if (ecology.get("eligibility") instanceof Map<?, ?> elig) {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) elig;
                for (String k : List.of("threshold_version", "rule_score_threshold", "rule_score_max",
                        "eligible_count", "returned_count", "total_scored", "rejected_by_reason",
                        "status", "generated_at", "requested_trade_date",
                        "pit_limited", "master_source", "master_reliability", "universe_as_of", "universe_size",
                        "data_cutoff", "candidate_invalidation", "daily_coverage", "daily_expected", "daily_covered",
                        "quality_status", "score_type", "score_model", "calibrator_version", "probability_verified", "fallback_reason",
                        "max_rule_score", "limit_up_excluded")) {
                    if (e.containsKey(k)) {
                        out.put(k, e.get(k));
                    }
                }
            }
        }
        out.putIfAbsent("quality_status", "UNKNOWN_LEGACY");
        out.putIfAbsent("probability_verified", false);
        out.putIfAbsent("data_cutoff", rec.getTradeDate().toString());
        out.putIfAbsent("candidate_invalidation", "仅用于信号日之后的下一交易日观察；历史清单不能继续作为当前候选");
        // 可追溯性补口（D-T1-03）：note 此前只在生成响应里构造，而快照复用路径
        // （generateCandidates 命中已有快照时直接走本方法）并不会重新生成它，
        // 于是「这份清单是什么口径、为什么空」在复用/回溯时看不到——
        // 与上面的 threshold_version/rule_score_threshold 同属一类缺口。
        out.putIfAbsent("note", "T+1 次日候选（快照回放）：基于信号日 "
                + rec.getTradeDate() + " 收盘数据生成；rule_score 为 0-" + RULE_SCORE_MAX
                + " 截面排序分，非概率；入选需 rule_score≥" + effectiveRuleScoreThreshold()
                + "（口径版本 " + eligibilityThresholdVersion + "）"
                + "；行情核对与完整说明见 ecology 与生成响应");
        out.put("found", true);
        return out;
    }

    /**
     * T+1 策略历史累计绩效：聚合区间内所有已核销候选的命中率/平均次日涨幅/高开，
     * 每日命中序列，并对照沪深300基准判断是否跑赢。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> strategyStats(LocalDate startDate, LocalDate endDate) {
        List<T1Candidate> recs = t1CandidateRepository
                .findByTradeDateBetweenOrderByTradeDateAsc(startDate, endDate);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("days", recs.size());

        if (recs.isEmpty()) {
            out.put("note", "区间内无候选快照");
            return out;
        }

        int totalSettled = 0;
        int totalHit = 0;
        int totalCandidateSlots = 0;
        int zeroCandidateDays = 0;
        int unsettledDays = 0;
        double retSum = 0;
        double gapSum = 0;
        List<Map<String, Object>> dailySeries = new ArrayList<>();
        // 基准逐日对齐：把「信号日 → 次日」的策略收益与「同一持有区间」的指数收益配对比较。
        // 旧实现用 策略日均次日涨幅 对比 指数区间总收益/(区间天数-1)，两条腿的区间、股票池、
        // 成本口径都不同（策略腿 gross、指数腿 gross，且末尾日期错配），结论不可比（D-T1-04）。
        Map<LocalDate, Double> indexClose = new HashMap<>();
        try {
            List<com.quant.entity.IndexQuote> quotes = indexQuoteRepository
                    .findByIndexCodeAndTradeDateBetweenOrderByTradeDateAsc("000300", startDate, endDate.plusDays(10));
            for (com.quant.entity.IndexQuote quote : quotes) {
                if (quote.getClosePrice() != null && quote.getClosePrice().signum() > 0) {
                    indexClose.put(quote.getTradeDate(), quote.getClosePrice().doubleValue());
                }
            }
        } catch (Exception e) {
            log.warn("基准指数加载失败: {}", e.getMessage());
        }
        double pairedStrategySum = 0;
        double pairedIndexSum = 0;
        int pairedDays = 0;
        int outperformDays = 0;
        int actualCandidates = 0;
        for (T1Candidate rec : recs) {
            Map<String, Object> settle = parseMap(rec.getSettledJson());
            Map<String, Object> summary = settle.get("summary") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            boolean complete = Boolean.TRUE.equals(rec.getSettled());
            int settled = complete ? numInt(summary.get("settled")) : 0;
            int hit = complete ? numInt(summary.get("hit_count")) : 0;
            double avgRet = numDouble(summary.get("avg_next_ret"));
            double avgGap = numDouble(summary.get("avg_gap"));
            totalSettled += settled;
            totalHit += hit;
            retSum += avgRet * settled;
            gapSum += avgGap * settled;
            // 入参 topN 只是上限：用各条记录自己的 topN 求和，不用第一条代表全部（D-T1-05）
            totalCandidateSlots += rec.getTopN() != null ? rec.getTopN() : 0;
            int candidateCount = parseList(rec.getCandidatesJson()).size();
            actualCandidates += candidateCount;
            if (settled == 0) {
                // 无候选日属于弃权；未完整核销属于证据缺失，两者分别披露，不伪造成命中或未命中。
                if (candidateCount == 0) {
                    zeroCandidateDays++;
                } else {
                    unsettledDays++;
                }
            }

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("trade_date", rec.getTradeDate().toString());
            day.put("settled", settled);
            day.put("hit_count", hit);
            day.put("hit_rate", settled > 0 ? round4((double) hit / settled) : 0);
            day.put("avg_next_ret", settled > 0 ? round4(avgRet) : null);
            day.put("status", candidateCount == 0 ? "NO_ELIGIBLE_CANDIDATE" : complete ? "SETTLED" : "UNSETTLED");
            // 逐日 策略腿 vs 指数腿
            LocalDate nextDay = settle.get("next_trade_date") instanceof String s
                    ? LocalDate.parse(s) : null;
            Double signalClose = indexClose.get(rec.getTradeDate());
            Double nextClose = nextDay != null ? indexClose.get(nextDay) : null;
            Double idxRet = signalClose != null && nextClose != null ? nextClose / signalClose - 1 : null;
            if (settled > 0 && idxRet != null) {
                day.put("index_next_ret", round4(idxRet));
                day.put("excess_ret", round4(avgRet - idxRet));
                pairedStrategySum += avgRet;
                pairedIndexSum += idxRet;
                pairedDays++;
                if (avgRet > idxRet) {
                    outperformDays++;
                }
            } else if (settled > 0) {
                day.put("index_next_ret", null);
                day.put("excess_ret", null);
            }
            dailySeries.add(day);
        }

        double hitRate = totalSettled > 0 ? (double) totalHit / totalSettled : 0;
        double avgNextRet = totalSettled > 0 ? retSum / totalSettled : 0;
        double avgGap = totalSettled > 0 ? gapSum / totalSettled : 0;

        Map<String, Object> benchmark = Map.of("available", false);
        if (pairedDays > 0) {
            double stratAvg = pairedStrategySum / pairedDays;
            double indexAvg = pairedIndexSum / pairedDays;
            // 键值对超过 Map.of 的 10 组上限，用 LinkedHashMap 保持稳定顺序
            Map<String, Object> bench = new LinkedHashMap<>();
            bench.put("available", true);
            bench.put("index_code", "000300");
            bench.put("index_name", "沪深300");
            bench.put("paired_days", pairedDays);
            bench.put("strategy_avg_daily_return", round4(stratAvg));
            bench.put("index_avg_daily_return", round4(indexAvg));
            bench.put("excess_avg_daily_return", round4(stratAvg - indexAvg));
            bench.put("outperform_days", outperformDays);
            bench.put("outperform_rate", round4((double) outperformDays / pairedDays));
            bench.put("outperform", stratAvg > indexAvg);
            bench.put("comparison_basis", "同持有区间逐日配对：候选等权「次日收盘/当日收盘」对沪深300 对应交易日涨跌幅");
            bench.put("caveats", List.of(
                    "策略腿为等权、未扣费用与滑点（T+1 口径为 close-to-close 方向统计，非可交易收益）",
                    "T 日收盘并非可成交价，实际入场点多在次日开盘，故本对比不等于可执行超额收益",
                    "指数腿为沪深300 点位涨跌幅，与候选股票池成分不同，不构成同池基准"));
            benchmark = bench;
        }

        out.put("total_candidates", actualCandidates);
        out.put("requested_candidate_slots", totalCandidateSlots);
        out.put("total_settled", totalSettled);
        out.put("hit_count", totalHit);
        out.put("hit_rate", round4(hitRate));
        out.put("avg_next_ret", round4(avgNextRet));
        out.put("avg_gap", round4(avgGap));
        out.put("settled_days", recs.size() - unsettledDays - zeroCandidateDays);
        out.put("zero_candidate_days", zeroCandidateDays);
        out.put("unsettled_days", unsettledDays);
        out.put("daily_series", dailySeries);
        out.put("benchmark", benchmark);
        out.put("note", "次日方向统计：命中=次日收盘>信号日收盘；信号日收盘并非生成后可成交价，"
                + "未扣费用和滑点，不是可执行策略收益。avg_next_ret 为所有已完整核销候选的毛收益均值；"
                + "基准比较按相同信号日和核销日、逐日等权配对；hit_rate 分母为已完整核销的候选数，"
                + "zero_candidate_days/unsettled_days 为被排除的日数，须与 hit_rate 同屏解读");
        return out;
    }

    private static int numInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static double numDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    /** 最近生成的候选列表（摘要）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentCandidates(int limit) {
        int n = Math.max(1, Math.min(limit > 0 ? limit : 20, 50));
        List<Map<String, Object>> out = new ArrayList<>();
        for (T1Candidate r : t1CandidateRepository.findTop30ByOrderByTradeDateDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trade_date", r.getTradeDate().toString());
            m.put("top_n", r.getTopN());
            m.put("settled", Boolean.TRUE.equals(r.getSettled()));
            out.add(m);
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    /**
     * T+1 规则分：短期动量 + 量能 + 趋势位置（可行域 0-{@link #RULE_SCORE_MAX}）。
     *
     * <p><b>已移除「涨停基因」项（D-T1-01）</b>：{@code +30 涨停} 与 {@code +min(15, 连板*5)}
     * 对进入本方法的打分池恒为 0——池内标的已在预过滤阶段被 {@code excludeLimitUpAtClose=true} 排除，
     * 其 {@code is_limit_up} 与 {@code lianban_days} 恒为 false/0。保留它们会让「公式声称的可行域 0-100」
     * 与「实际可行域 0-50」长期不一致，并使按 0-100 设置的阈值既不可达也不可解释。
     *
     * <p>三项目前均有可达区间：动量 {@code [-15,20]}、量能 {@code [-10,15]}、趋势 {@code [-10,15]}，
     * 合计 {@code [-35,50]}，再 clamp 到 {@code [0,50]}。
     */
    private static double scoreT1(Map<String, Object> lu, double[] ind) {
        double score = 0;
        double ret5 = ind[1];
        score += Math.max(-15, Math.min(20, ret5 * 100 * 0.4));
        double vr = ((Number) lu.getOrDefault("volume_ratio", 0)).doubleValue();
        score += Math.max(-10, Math.min(15, (vr - 1) * 10));
        score += Math.max(-10, Math.min(15, ind[2] * 15));
        return Math.max(0, Math.min(RULE_SCORE_MAX, score));
    }

    /**
     * 次日可买性/风险预判（收盘后即可用）：
     * 一字板风险（昨涨停+连板≥2）、涨停次日高开、历史高开均值偏高、量比过大。
     */
    private static Map<String, Object> buildPreTrade(Map<String, Object> lu, Map<String, Object> premium) {
        Map<String, Object> pre = new LinkedHashMap<>();
        List<String> risks = new ArrayList<>();
        boolean isLimitUp = Boolean.TRUE.equals(lu.get("is_limit_up"));
        int lianban = ((Number) lu.getOrDefault("lianban_days", 0)).intValue();
        double volumeRatio = ((Number) lu.getOrDefault("volume_ratio", 0)).doubleValue();
        double avgGap = premium.get("avg_gap") instanceof Number num ? num.doubleValue() : 0;

        String buyability = "待盘中核实";
        if (isLimitUp && lianban >= 2) {
            risks.add("预计一字板");
            buyability = "大概率买不进";
        } else if (isLimitUp) {
            risks.add("涨停次日高开");
            buyability = "高开追高谨慎";
        }
        if (isLimitUp && volumeRatio >= 3.0) {
            risks.add("量比过大");
        }
        if (isLimitUp && avgGap >= 0.03) {
            risks.add("历史高开均值偏高");
        }
        if (isLimitUp && risks.isEmpty()) {
            risks.add("涨停次日波动大");
        }
        pre.put("buyability", buyability);
        pre.put("risk_tags", risks);
        pre.put("is_limit_up", isLimitUp);
        pre.put("lianban_days", lianban);
        pre.put("hist_avg_gap", round4(avgGap));
        return pre;
    }

    /** 短期指标：ret1, ret5, 相对 MA20 位置（close/ma20 - 1）。 */
    private static double[] shortIndicators(List<MarketData> bars) {
        int n = bars.size();
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = bars.get(i).getClosePrice() != null ? bars.get(i).getClosePrice().doubleValue() : 0;
        }
        double ret1 = n >= 2 && closes[n - 2] > 0 ? closes[n - 1] / closes[n - 2] - 1 : 0;
        double ret5 = n >= 6 && closes[n - 6] > 0 ? closes[n - 1] / closes[n - 6] - 1 : 0;
        int maLen = Math.min(20, n);
        double maSum = 0;
        for (int i = n - maLen; i < n; i++) {
            maSum += closes[i];
        }
        double ma20 = maLen > 0 ? maSum / maLen : 0;
        double maPos = ma20 > 0 ? closes[n - 1] / ma20 - 1 : 0;
        return new double[]{ret1, ret5, maPos};
    }

    private static List<String> buildReasons(Map<String, Object> lu, double[] ind) {
        List<String> reasons = new ArrayList<>();
        if (Boolean.TRUE.equals(lu.get("is_limit_up"))) {
            reasons.add("当日涨停");
        }
        int lianban = ((Number) lu.getOrDefault("lianban_days", 0)).intValue();
        if (lianban >= 2) {
            reasons.add(lianban + " 连板");
        }
        double vr = ((Number) lu.getOrDefault("volume_ratio", 0)).doubleValue();
        if (vr >= 1.5) {
            reasons.add("量比放大 " + round1(vr) + "x");
        }
        if (ind[1] >= 0.05) {
            reasons.add("近 5 日涨 " + round1(ind[1] * 100) + "%");
        }
        if (ind[2] >= 0.02) {
            reasons.add("站上 MA20");
        }
        if (reasons.isEmpty()) {
            reasons.add("动量/量能相对占优");
        }
        return reasons;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            // V2-MP-030: 禁止损坏 JSON 静默降级为空 list 并标成功；fail-closed
            throw new IllegalStateException("T1 候选模型 JSON 损坏，禁止静默降级", e);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // V2-MP-030: 禁止损坏 JSON 静默降级为空 map 并标成功；fail-closed
            throw new IllegalStateException("T1 候选模型 JSON 损坏，禁止静默降级", e);
        }
    }

    private boolean dailyBarClosed(LocalDate day) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
        return day.isBefore(now.toLocalDate())
                || day.equals(now.toLocalDate()) && !now.toLocalTime().isBefore(java.time.LocalTime.of(15, 0));
    }

    private static boolean validDailyBar(MarketData bar) {
        if (bar == null) return false;
        for (java.math.BigDecimal price : new java.math.BigDecimal[]{
                bar.getOpenPrice(), bar.getHighPrice(), bar.getLowPrice(), bar.getClosePrice()}) {
            if (price == null || price.signum() <= 0 || !Double.isFinite(price.doubleValue())) return false;
        }
        return bar.getHighPrice().compareTo(bar.getOpenPrice().max(bar.getClosePrice())) >= 0
                && bar.getLowPrice().compareTo(bar.getOpenPrice().min(bar.getClosePrice())) <= 0;
    }

    /**
     * 该日是否为交易日历**已知**的交易日。
     * <p>返回 false 有两种含义：「已知为非交易日」与「日历根本不覆盖该日」。
     * 调用方必须结合数据是否存在来区分，不得直接把 false 当作「休市」。
     */
    private boolean calendarKnowsTradingDay(LocalDate day) {
        if (day == null || tradingCalendarService == null) {
            return false;
        }
        try {
            return tradingCalendarService.isTradingDay(day);
        } catch (RuntimeException exc) {
            // 日历不可用时不得把「查不到」当成「非交易日」，否则会把数据缺失
            // 误报成休市。此处保守地返回 false，由调用方走 UNKNOWN 分支。
            return false;
        }
    }

    /**
     * 该日是否落在交易日历的覆盖区间内。
     * <p>用于把 {@code isTradingDay == false} 拆成两种语义：
     * 「区间内且非交易日」（可断言休市）与「区间外」（不可断言，未知）。
     * 日历不可用或为空时返回 false（保守，走 UNKNOWN）。
     */
    private boolean calendarCoversDay(LocalDate day) {
        if (day == null || tradingCalendarService == null) {
            return false;
        }
        try {
            LocalDate[] window = tradingCalendarService.coverageWindow();
            return window != null && !day.isBefore(window[0]) && !day.isAfter(window[1]);
        } catch (RuntimeException exc) {
            return false;
        }
    }

    /**
     * 空决策响应。
     *
     * <p><b>P0-3（2026-10-13 修正）</b>：
     * <ul>
     *   <li>删除了恒为 {@code false} 的 {@code data_date_fallback}。
     *       本服务**没有**任何回退路径（{@code actualDate} 自始至终等于 {@code tradeDate}），
     *       保留一个永远为 false 的字段会暗示「存在回退能力且本次未触发」，
     *       属于虚假的诚实信号。设计意图是「不回退、只阻断」，现在由
     *       {@code calendar_trading_day} 与状态码如实表达。</li>
     *   <li>新增 {@code calendar_trading_day}：明确该日在日历上的判定，
     *       使「非交易日」与「交易日但数据缺失」可区分。</li>
     * </ul>
     *
     * @param calendarTradingDay 该日是否为日历已知的交易日；调用方须显式传入
     */
    private static Map<String, Object> emptyDecision(LocalDate day, String status, String note,
                                                    boolean calendarTradingDay) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", day.toString());
        out.put("requested_trade_date", day.toString());
        // data_cutoff 是如实字段：本服务从不使用与请求日不同的数据日
        out.put("data_cutoff", day.toString());
        out.put("calendar_trading_day", calendarTradingDay);
        out.put("top_n", 0);
        out.put("total_scored", 0);
        out.put("candidates", List.of());
        out.put("settled", false);
        out.put("score_type", "RULE_SCORE");
        out.put("probability_verified", false);
        out.put("status", status);
        out.put("quality_status", status);
        out.put("note", note);
        return out;
    }

    /** 加载结果：模型可空；fallbackReason 在无可用模型时说明原因。 */
    private record T1ProbLoadResult(T1ProbModel model, String fallbackReason) {}

    /** 加载 horizon=1 的 T+1 概率模型：仅允许 stage=champion。
     * 禁止回退到最新 challenger（未发布模型不得自动上线）。
     * 当前训练特征为通用 selector_result.rule_score，与 T+1 专用 scoreT1 分布不一致时也拒绝推理。 */
    private T1ProbLoadResult loadT1ProbModelWithReason(LocalDate asOf) {
        try {
            String schemaHash = LogisticModelTrainer.ruleScoreSchemaHash();
            String deployKey = com.quant.research.artifact.ModelImportService.buildDeployKey(
                    LogisticModelTrainer.ALGORITHM, FEATURE_SET_VERSION, schemaHash);
            com.quant.entity.ModelVersion mv = modelVersionRepository
                    .findFirstByDeployKeyAndStageAndStrategyTypeAndHorizonOrderByCreatedAtDesc(
                            deployKey, "champion", "T1", ResearchConvention.HORIZON_T1)
                    .orElse(null);
            if (mv == null || mv.getArtifact() == null || mv.getArtifact().isBlank()) {
                return new T1ProbLoadResult(null, "NO_CHAMPION_MODEL");
            }
            if (mv.getCreatedAt() == null || mv.getCreatedAt().toLocalDate().isAfter(asOf)) {
                return new T1ProbLoadResult(null, "MODEL_NOT_AVAILABLE_AS_OF");
            }
            // 特征语义门禁：未声明 T+1 专用 schema 的通用 rule_score 模型不得用于 scoreT1
            if (!ModelDeploymentContract.isContractComplete(mv)
                    || !ModelDeploymentContract.contextMismatches(mv, "T1", ResearchConvention.HORIZON_T1,
                    FEATURE_SET_VERSION, schemaHash, LogisticModelTrainer.OUTPUT_KIND).isEmpty()
                    || !deployKey.equals(mv.getDeployKey())) {
                return new T1ProbLoadResult(null, "MODEL_CONTRACT_MISMATCH");
            }
            if (!mv.getArtifactHash().equalsIgnoreCase(ArtifactManifestValidator.sha256Hex(mv.getArtifact()))) {
                return new T1ProbLoadResult(null, "MODEL_HASH_MISMATCH");
            }
            Map<String, Object> art = objectMapper.readValue(mv.getArtifact(), new TypeReference<Map<String, Object>>() {});
            List<Number> weightsRaw = castNumberList(art.get("weights"));
            Number biasRaw = art.get("bias") instanceof Number num ? num : null;
            if (weightsRaw == null || weightsRaw.size() != 1 || biasRaw == null
                    || !Double.isFinite(biasRaw.doubleValue())
                    || !List.of("rule_score").equals(art.get("features"))) {
                return new T1ProbLoadResult(null, "ARTIFACT_INVALID");
            }
            double[] weights = weightsRaw.stream().mapToDouble(Number::doubleValue).toArray();
            double bias = biasRaw != null ? biasRaw.doubleValue() : 0;
            double featureMin = Double.NaN;
            double featureMax = Double.NaN;
            Object pre = art.get("preprocessor");
            if (pre instanceof Map<?, ?> preMap) {
                if (!"min_max".equals(preMap.get("type")) || !"train_only".equals(preMap.get("fit_window"))) {
                    return new T1ProbLoadResult(null, "PREPROCESSOR_INVALID");
                }
                List<Number> minList = castNumberList(preMap.get("min"));
                List<Number> maxList = castNumberList(preMap.get("max"));
                if (minList != null && !minList.isEmpty()) {
                    featureMin = minList.get(0).doubleValue();
                }
                if (maxList != null && !maxList.isEmpty()) {
                    featureMax = maxList.get(0).doubleValue();
                }
            }
            // D-T1-01 后续守卫（2026-10-13）：rule_score 的取值域已由 0–100 收敛到 0–50
            // （移除恒不可达的涨停/连板加分项）。用旧 0–100 标度训练出的模型，其
            // preprocessor.max 会显著大于当前 RULE_SCORE_MAX，min-max 归一化会把当前分数
            // 系统性压到实际区间的一半，再经 sigmoid 得到**被静默压低的伪概率**。
            // 这里 fail-closed：标度不匹配即拒绝推理并回退规则分，不输出错误标度的概率。
            if (!Double.isFinite(featureMin) || !Double.isFinite(featureMax) || featureMin < 0
                    || featureMax <= featureMin || featureMax > RULE_SCORE_MAX) {
                log.warn("T+1 champion 模型 {} 的 preprocessor.max={} 与当前 rule_score 值域 0–{} 不匹配"
                                + "（疑似按旧 0–100 标度训练），拒绝推理并回退规则分",
                        mv.getModelVersion(), featureMax, RULE_SCORE_MAX);
                return new T1ProbLoadResult(null,
                        "PREPROCESSOR_SCALE_MISMATCH:max=" + featureMax + ",expected<=0-" + RULE_SCORE_MAX);
            }
            Calibrator cal = calibratorService.loadChampion("T1", ResearchConvention.HORIZON_T1,
                    mv.getModelVersion()).orElse(null);
            if (cal == null) {
                return new T1ProbLoadResult(null, "NO_CHAMPION_CALIBRATOR");
            }
            if (!ModelDeploymentContract.calibratorPairMismatches(mv, cal).isEmpty()
                    || !mv.getOutputKind().equals(cal.getBaseOutputKind())
                    || cal.getFitEnd() == null || cal.getEvalStart() == null || cal.getEvalEnd() == null
                    || !cal.getEvalStart().isAfter(cal.getFitEnd()) || cal.getEvalEnd().isBefore(cal.getEvalStart())
                    || !cal.getEvalEnd().isBefore(asOf) || cal.getCreatedAt() == null
                    || cal.getCreatedAt().toLocalDate().isAfter(asOf)
                    || cal.getCalibratorJson() == null || cal.getCalibratorHash() == null
                    || !cal.getCalibratorHash().equalsIgnoreCase(ArtifactManifestValidator.sha256Hex(cal.getCalibratorJson()))
                    || !calibratorPayloadValid(cal)) {
                return new T1ProbLoadResult(null, "CALIBRATOR_EVIDENCE_INVALID");
            }
            return new T1ProbLoadResult(
                    new T1ProbModel(weights, bias, featureMin, featureMax, mv.getModelVersion(), cal),
                    null);
        } catch (Exception e) {
            log.warn("加载 T+1 概率模型失败，回退规则分: {}", e.getMessage());
            return new T1ProbLoadResult(null, "LOAD_ERROR:" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Number> castNumberList(Object value) {
        if (value instanceof List<?> list) {
            if (list.stream().allMatch(v -> v instanceof Number n && Double.isFinite(n.doubleValue()))) {
                return (List<Number>) list;
            }
        }
        return null;
    }

    /** 已加载的 T+1 概率模型（含特征 min/max 预处理，推理时用同一组参数）。 */
    private boolean calibratorPayloadValid(Calibrator cal) throws Exception {
        Map<String, Object> spec = objectMapper.readValue(cal.getCalibratorJson(), new TypeReference<>() {});
        if (!cal.getOutputKind().equals(spec.get("kind")) || !cal.getInputDomain().equals(spec.get("input_domain"))) {
            return false;
        }
        if (!Calibrator.INPUT_RULE_SCORE.equals(cal.getInputDomain())
                && !Calibrator.INPUT_BASE_PROBABILITY.equals(cal.getInputDomain())) return false;
        if (Calibrator.KIND_PAV_ISOTONIC.equals(cal.getOutputKind())) {
            if (!(spec.get("breakpoints") instanceof List<?> points) || points.isEmpty()) return false;
            double previousHi = Double.NEGATIVE_INFINITY;
            double previousProb = 0;
            for (Object point : points) {
                if (!(point instanceof Map<?, ?> p) || !(p.get("score_lo") instanceof Number lo)
                        || !(p.get("score_hi") instanceof Number hi) || !(p.get("calibrated_prob") instanceof Number prob)
                        || !Double.isFinite(lo.doubleValue()) || !Double.isFinite(hi.doubleValue())
                        || !Double.isFinite(prob.doubleValue()) || lo.doubleValue() < previousHi
                        || hi.doubleValue() < lo.doubleValue() || prob.doubleValue() < previousProb || prob.doubleValue() > 1) return false;
                previousHi = hi.doubleValue();
                previousProb = prob.doubleValue();
            }
            return true;
        }
        if (!Calibrator.KIND_PLATT_SIGMOID.equals(cal.getOutputKind())
                || !(spec.get("norm") instanceof Map<?, ?> norm) || !(spec.get("sigmoid") instanceof Map<?, ?> sig)
                || !(norm.get("min") instanceof Number min) || !(norm.get("max") instanceof Number max)
                || !(sig.get("a") instanceof Number a) || !(sig.get("b") instanceof Number b)) return false;
        double upper = Calibrator.INPUT_RULE_SCORE.equals(cal.getInputDomain()) ? RULE_SCORE_MAX : 1;
        return Double.isFinite(min.doubleValue()) && Double.isFinite(max.doubleValue())
                && min.doubleValue() >= 0 && max.doubleValue() <= upper && max.doubleValue() > min.doubleValue()
                && Double.isFinite(a.doubleValue()) && Double.isFinite(b.doubleValue());
    }

    private record T1ProbModel(double[] weights, double bias, double featureMin, double featureMax,
                               String modelVersion, Calibrator calibrator) {
        double predictProbability(CalibratorService service, double ruleScore) {
            if (Calibrator.INPUT_RULE_SCORE.equals(calibrator.getInputDomain())) {
                return service.apply(calibrator, ruleScore);
            }
            double range = featureMax - featureMin;
            double norm = range > 1e-12 ? (ruleScore - featureMin) / range : 0.5;
            double z = bias;
            if (weights.length > 0) {
                z += weights[0] * norm;
            }
            if (z >= 0) {
                double e = Math.exp(-z);
                return service.apply(calibrator, 1.0 / (1.0 + e));
            }
            double e = Math.exp(z);
            return service.apply(calibrator, e / (1.0 + e));
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
