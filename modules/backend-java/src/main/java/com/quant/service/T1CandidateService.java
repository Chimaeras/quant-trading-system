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
 * <p>打分：涨停基因（涨停/连板/量比/换手）+ 短期动量 + 量能 + 趋势位置，输出为
 * <b>rule_score（0-100，截面排序用，非概率）</b>；候选 JSON 标注 score_type 以区别于概率。
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
    private static final String ELIGIBILITY_THRESHOLD_VERSION = "t1_rule_v1";
    /** rule_score 入选下限（0-100，截面排序分，非概率）。弱信号日宁可无标的也不凑满。 */
    private static final double RULE_SCORE_THRESHOLD = 40.0;
    /** 概率轨入选下限：仅当加载到 T+1 champion 概率模型时启用（绝对概率，非截面 percentile）。 */
    private static final double PROBABILITY_THRESHOLD = 0.55;

    private final MarketDataRepository marketDataRepository;
    private final PointInTimeUniverseProvider pointInTimeUniverseProvider;
    private final T1CandidateRepository t1CandidateRepository;
    private final LimitUpEcologyService limitUpEcologyService;
    private final TradingCalendarService tradingCalendarService;
    private final com.quant.repository.ModelVersionRepository modelVersionRepository;
    private final com.quant.repository.IndexQuoteRepository indexQuoteRepository;
    private final ObjectMapper objectMapper;

    /** 生成 T 日次日候选清单并落库。 */
    @Transactional
    public Map<String, Object> generateCandidates(LocalDate tradeDate, int topN) {
        int n = Math.max(1, Math.min(topN > 0 ? topN : DEFAULT_TOP_N, 50));
        LocalDate rangeStart = tradeDate.minusDays(LOOKBACK_DAYS);
        PointInTimeUniverseProvider.UniverseView universe = pointInTimeUniverseProvider.loadAsOf(tradeDate);

        // 区间行情（单次批量），按股票分组
        Map<String, List<MarketData>> byStock = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate)
                .stream()
                .collect(Collectors.groupingBy(
                        m -> m.getStockCode() + "|" + m.getExchangeType(), LinkedHashMap::new, Collectors.toList()));
        Map<String, MarketData> todayByKey = marketDataRepository.findByTradeDate(tradeDate).stream()
                .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));

        // 信号日无行情（今日未收盘/非交易日/数据未覆盖）时，回退到该日或之前最近的有行情交易日，
        // 避免打分循环起点为空导致返回空候选。byStock 已覆盖 [rangeStart, tradeDate]，
        // actualDate ≤ tradeDate 时其行情必然已在 byStock 内，无需重新加载区间数据。
        LocalDate actualDate = tradeDate;
        if (todayByKey.isEmpty()) {
            actualDate = marketDataRepository.findLatestTradeDateOnOrBefore(tradeDate).orElse(null);
            if (actualDate == null) {
                log.warn("T+1 候选生成失败：tradeDate={} 及此前均无行情数据", tradeDate);
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("trade_date", tradeDate.toString());
                empty.put("top_n", 0);
                empty.put("total_scored", 0);
                empty.put("candidates", List.of());
                empty.put("settled", false);
                empty.put("note", "所选信号日及此前均无行情数据，请确认数据已入库");
                return empty;
            }
            log.info("信号日 {} 无行情，回退到最近有行情交易日 {}", tradeDate, actualDate);
            todayByKey = marketDataRepository.findByTradeDate(actualDate).stream()
                    .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));
            universe = pointInTimeUniverseProvider.loadAsOf(actualDate);
        }

        Map<String, StockInfo> infoByKey = universe.byKey();

        // 涨停股次日溢价历史分布（用于次日可买性/高开追高风险预判）
        // 注意：生成阶段只能用 ≤ T-1 的历史数据，不能读 T+1（否则未来函数）。
        // historicalNextDayPremium 只统计信号日 ≤ T-2 的涨停事件，数据截止 T-1。
        Map<String, Object> premium = limitUpEcologyService.historicalNextDayPremium(actualDate, 50);

        // 尝试加载 horizon=1 的 champion 概率模型；无匹配 champion 则仅输出规则分
        T1ProbLoadResult modelLoad = loadT1ProbModelWithReason();
        T1ProbModel probModel = modelLoad.model();
        String fallbackReason = modelLoad.fallbackReason();

        List<Map<String, Object>> scored = new ArrayList<>();
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
            String name = info != null ? info.getStockName() : code;
            if (!ScoringUtils.isTradableForScoring(stockBars, code, exchange, name,
                    info != null ? info.getStatus() : null, true, true, true)) {
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
                double prob = probModel.predictProbability(ruleScore);
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

        // ===== 入选阈值门禁（V2-MP-027）：先过滤 eligible，再排序截断 =====
        // TopN 只是上限（cap），不是配额（quota）。弱信号日允许返回 0 条并明确标注。
        Map<String, Object> eligibility = applyEligibilityGate(scored, probModel);
        List<Map<String, Object>> top = new ArrayList<>((List<Map<String, Object>>) eligibility.get("top"));
        Map<String, Integer> rejectedByReason = (Map<String, Integer>) eligibility.get("rejectedByReason");
        int eligibleCount = (Integer) eligibility.get("eligibleCount");

        LocalDate effectiveDate = actualDate;
        T1Candidate rec = t1CandidateRepository.findByTradeDate(effectiveDate).orElseGet(() -> {
            T1Candidate c = new T1Candidate();
            c.setTradeDate(effectiveDate);
            return c;
        });
        rec.setTopN(n);
        rec.setCandidatesJson(toJson(top));
        rec.setSettled(Boolean.FALSE);
        rec.setSettledJson(null);
        // 阈值门禁元数据随生态快照落库（避免 DB 迁移），供回溯/核销时溯源
        Map<String, Object> ecology = new LinkedHashMap<>(limitUpEcologyService.ecology(actualDate, 30));
        Map<String, Object> eligMeta = new LinkedHashMap<>();
        eligMeta.put("threshold_version", ELIGIBILITY_THRESHOLD_VERSION);
        eligMeta.put("rule_score_threshold", RULE_SCORE_THRESHOLD);
        eligMeta.put("probability_threshold", probModel != null ? PROBABILITY_THRESHOLD : null);
        eligMeta.put("eligible_count", eligibleCount);
        eligMeta.put("rejected_by_reason", rejectedByReason);
        eligMeta.put("status", top.isEmpty() ? "NO_ELIGIBLE_CANDIDATE" : "OK");
        ecology.put("eligibility", eligMeta);
        rec.setEcologyJson(toJson(ecology));
        t1CandidateRepository.save(rec);

        boolean noEligible = top.isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", actualDate.toString());
        out.put("requested_trade_date", tradeDate.toString());
        out.put("top_n", top.size());
        out.put("total_scored", scored.size());
        out.put("eligible_count", eligibleCount);
        out.put("rejected_count", scored.size() - eligibleCount);
        out.put("rejected_by_reason", rejectedByReason);
        out.put("threshold_version", ELIGIBILITY_THRESHOLD_VERSION);
        out.put("candidates", top);
        out.put("settled", false);
        out.put("status", noEligible ? "NO_ELIGIBLE_CANDIDATE" : "OK");
        if (probModel != null) {
            out.put("score_model", probModel.modelVersion());
            out.put("score_type", "CALIBRATED_PROBABILITY");
            out.put("fallback_reason", null);
            out.put("note", "T+1 次日候选：已加载 champion 概率模型 " + probModel.modelVersion()
                    + "，按校准概率排序（rule_score 见各候选字段）；入选需 rule_score≥"
                    + RULE_SCORE_THRESHOLD + " 且概率≥" + PROBABILITY_THRESHOLD
                    + (noEligible ? "；当日无合格标的（NO_ELIGIBLE_CANDIDATE），宁可空不凑数" : "")
                    + "；点击「次日核销」在 T+1 结束后回填表现");
        } else {
            out.put("score_type", "RULE_SCORE");
            out.put("score_model", null);
            out.put("fallback_reason", fallbackReason != null ? fallbackReason
                    : "NO_CHAMPION_MODEL");
            out.put("note", "T+1 次日候选：仅输出 RULE_SCORE（涨停基因+短期动量+量能+趋势），非概率；"
                    + "入选需 rule_score≥" + RULE_SCORE_THRESHOLD
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
     * <p>门槛：可交易/流动性/上市时长（打分前已过滤）+ rule_score≥{@link #RULE_SCORE_THRESHOLD}
     * +（若加载了概率模型）校准概率≥{@link #PROBABILITY_THRESHOLD}。被拒原因逐条计数，
     * 用于响应与快照中的 rejected_by_reason。TopN 只是上限：eligible 不足时原样返回，不补足。
     */
    private Map<String, Object> applyEligibilityGate(List<Map<String, Object>> scored, T1ProbModel probModel) {
        List<Map<String, Object>> eligible = new ArrayList<>();
        Map<String, Integer> rejectedByReason = new LinkedHashMap<>();
        for (Map<String, Object> c : scored) {
            double ruleScore = ((Number) c.get("rule_score")).doubleValue();
            if (ruleScore < RULE_SCORE_THRESHOLD) {
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("top", eligible);
        result.put("eligibleCount", eligible.size());
        result.put("rejectedByReason", rejectedByReason);
        return result;
    }

    /** T+1 结束后核销：回填候选次日表现并聚合命中率。 */
    @Transactional
    public Map<String, Object> settleCandidates(LocalDate tradeDate) {
        T1Candidate rec = t1CandidateRepository.findByTradeDate(tradeDate).orElse(null);
        if (rec == null) {
            return Map.of("status", "not_found", "message", "该日尚未生成候选，请先生成");
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
            if (next == null || next.getClosePrice() == null || next.getOpenPrice() == null || !Double.isFinite(closeT) || closeT <= 0) {
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

        rec.setSettled(Boolean.TRUE);
        rec.setSettledJson(toJson(Map.of(
                "next_trade_date", nextDay.toString(),
                "summary", summary,
                "results", results)));
        t1CandidateRepository.save(rec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("next_trade_date", nextDay.toString());
        out.put("summary", summary);
        out.put("results", results);
        out.put("note", "次日核销：hit=次日收盘>当日收盘；gap=次日开盘相对当日收盘；next_is_limit_up=次日收盘涨停（一字板可能买不进）");
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
            out.put("ecology", parseMap(rec.getEcologyJson()));
        }
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
                .findByTradeDateBetweenAndSettledTrueOrderByTradeDateAsc(startDate, endDate);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("days", recs.size());

        if (recs.isEmpty()) {
            out.put("note", "区间内无已核销候选，请先生成并核销");
            return out;
        }

        int totalSettled = 0;
        int totalHit = 0;
        double retSum = 0;
        double gapSum = 0;
        List<Map<String, Object>> dailySeries = new ArrayList<>();
        for (T1Candidate rec : recs) {
            Map<String, Object> settle = parseMap(rec.getSettledJson());
            Map<String, Object> summary = settle.get("summary") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            int settled = numInt(summary.get("settled"));
            int hit = numInt(summary.get("hit_count"));
            double avgRet = numDouble(summary.get("avg_next_ret"));
            double avgGap = numDouble(summary.get("avg_gap"));
            totalSettled += settled;
            totalHit += hit;
            retSum += avgRet * settled;
            gapSum += avgGap * settled;

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("trade_date", rec.getTradeDate().toString());
            day.put("settled", settled);
            day.put("hit_count", hit);
            day.put("hit_rate", settled > 0 ? round4((double) hit / settled) : 0);
            day.put("avg_next_ret", round4(avgRet));
            dailySeries.add(day);
        }

        double hitRate = totalSettled > 0 ? (double) totalHit / totalSettled : 0;
        double avgNextRet = totalSettled > 0 ? retSum / totalSettled : 0;
        double avgGap = totalSettled > 0 ? gapSum / totalSettled : 0;

        // 沪深300 基准
        Map<String, Object> benchmark = Map.of("available", false);
        try {
            List<com.quant.entity.IndexQuote> quotes = indexQuoteRepository
                    .findByIndexCodeAndTradeDateBetweenOrderByTradeDateAsc("000300", startDate, endDate);
            if (quotes.size() >= 2) {
                double first = quotes.get(0).getClosePrice() != null ? quotes.get(0).getClosePrice().doubleValue() : 0;
                double last = quotes.get(quotes.size() - 1).getClosePrice() != null
                        ? quotes.get(quotes.size() - 1).getClosePrice().doubleValue() : 0;
                double indexReturn = first > 0 ? last / first - 1 : 0;
                double indexAvgDaily = quotes.size() > 1 ? indexReturn / (quotes.size() - 1) : 0;
                benchmark = Map.of(
                        "available", true,
                        "index_code", "000300",
                        "index_name", "沪深300",
                        "index_return", round4(indexReturn),
                        "index_avg_daily_return", round4(indexAvgDaily),
                        "outperform", avgNextRet > indexAvgDaily);
            }
        } catch (Exception e) {
            log.warn("基准指数计算失败: {}", e.getMessage());
        }

        out.put("total_candidates", recs.size() * recs.get(0).getTopN());
        out.put("total_settled", totalSettled);
        out.put("hit_count", totalHit);
        out.put("hit_rate", round4(hitRate));
        out.put("avg_next_ret", round4(avgNextRet));
        out.put("avg_gap", round4(avgGap));
        out.put("daily_series", dailySeries);
        out.put("benchmark", benchmark);
        out.put("note", "策略绩效：命中=次日收盘>当日收盘；avg_next_ret=候选平均次日涨幅；"
                + "benchmark=沪深300区间收益与日均收益（outperform=策略日均次日涨幅>指数日均收益）");
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

    /** T+1 规则分：涨停基因 + 短期动量 + 量能 + 趋势位置。 */
    private static double scoreT1(Map<String, Object> lu, double[] ind) {
        double score = 0;
        if (Boolean.TRUE.equals(lu.get("is_limit_up"))) {
            score += 30;
        }
        int lianban = ((Number) lu.getOrDefault("lianban_days", 0)).intValue();
        score += Math.min(15, lianban * 5);
        double ret5 = ind[1];
        score += Math.max(-15, Math.min(20, ret5 * 100 * 0.4));
        double vr = ((Number) lu.getOrDefault("volume_ratio", 0)).doubleValue();
        score += Math.max(-10, Math.min(15, (vr - 1) * 10));
        score += Math.max(-10, Math.min(15, ind[2] * 15));
        return Math.max(0, Math.min(100, score));
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

        String buyability = "可买";
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

    /** 加载结果：模型可空；fallbackReason 在无可用模型时说明原因。 */
    private record T1ProbLoadResult(T1ProbModel model, String fallbackReason) {}

    /** 加载 horizon=1 的 T+1 概率模型：仅允许 stage=champion。
     * 禁止回退到最新 challenger（未发布模型不得自动上线）。
     * 当前训练特征为通用 selector_result.rule_score，与 T+1 专用 scoreT1 分布不一致时也拒绝推理。 */
    private T1ProbLoadResult loadT1ProbModelWithReason() {
        try {
            com.quant.entity.ModelVersion mv = modelVersionRepository
                    .findFirstByHorizonAndStageOrderByCreatedAtDesc(ResearchConvention.HORIZON_T1, "champion")
                    .orElse(null);
            if (mv == null || mv.getArtifact() == null || mv.getArtifact().isBlank()) {
                return new T1ProbLoadResult(null, "NO_CHAMPION_MODEL");
            }
            // 特征语义门禁：未声明 T+1 专用 schema 的通用 rule_score 模型不得用于 scoreT1
            String featureVersion = mv.getFeatureVersion();
            if (featureVersion == null || featureVersion.isBlank()
                    || "rule_score_v1".equalsIgnoreCase(featureVersion)
                    || featureVersion.toLowerCase().contains("selector_result")) {
                log.warn("T+1 champion 模型 {} 特征版本 {} 与 scoreT1 不兼容，回退规则分",
                        mv.getModelVersion(), featureVersion);
                return new T1ProbLoadResult(null, "FEATURE_SCHEMA_MISMATCH:" + featureVersion);
            }
            if (!featureVersion.toLowerCase().contains("t1")) {
                log.warn("T+1 champion 模型 {} 特征版本 {} 未标明 T+1 schema，回退规则分",
                        mv.getModelVersion(), featureVersion);
                return new T1ProbLoadResult(null, "FEATURE_SCHEMA_MISMATCH:" + featureVersion);
            }
            Map<String, Object> art = objectMapper.readValue(mv.getArtifact(), new TypeReference<Map<String, Object>>() {});
            List<Number> weightsRaw = castNumberList(art.get("weights"));
            Number biasRaw = art.get("bias") instanceof Number num ? num : null;
            if (weightsRaw == null || weightsRaw.isEmpty()) {
                return new T1ProbLoadResult(null, "ARTIFACT_INVALID");
            }
            double[] weights = weightsRaw.stream().mapToDouble(Number::doubleValue).toArray();
            double bias = biasRaw != null ? biasRaw.doubleValue() : 0;
            double featureMin = 0;
            double featureMax = 1;
            Object pre = art.get("preprocessor");
            if (pre instanceof Map<?, ?> preMap) {
                List<Number> minList = castNumberList(preMap.get("min"));
                List<Number> maxList = castNumberList(preMap.get("max"));
                if (minList != null && !minList.isEmpty()) {
                    featureMin = minList.get(0).doubleValue();
                }
                if (maxList != null && !maxList.isEmpty()) {
                    featureMax = maxList.get(0).doubleValue();
                }
            }
            return new T1ProbLoadResult(
                    new T1ProbModel(weights, bias, featureMin, featureMax, mv.getModelVersion()),
                    null);
        } catch (Exception e) {
            log.warn("加载 T+1 概率模型失败，回退规则分: {}", e.getMessage());
            return new T1ProbLoadResult(null, "LOAD_ERROR:" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Number> castNumberList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Number>) list;
        }
        return null;
    }

    /** 已加载的 T+1 概率模型（含特征 min/max 预处理，推理时用同一组参数）。 */
    private record T1ProbModel(double[] weights, double bias, double featureMin, double featureMax, String modelVersion) {
        double predictProbability(double ruleScore) {
            double range = featureMax - featureMin;
            double norm = range > 1e-12 ? (ruleScore - featureMin) / range : 0.5;
            double z = bias;
            if (weights.length > 0) {
                z += weights[0] * norm;
            }
            if (z >= 0) {
                double e = Math.exp(-z);
                return 1.0 / (1.0 + e);
            }
            double e = Math.exp(z);
            return e / (1.0 + e);
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
