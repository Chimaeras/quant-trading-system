package com.quant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.T1Candidate;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.T1CandidateRepository;
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
    private static final int LOOKBACK_DAYS = 40;
    private static final double MIN_AVG_AMOUNT = 30_000_000;
    private static final int MIN_LISTING_DAYS = 60;

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final T1CandidateRepository t1CandidateRepository;
    private final LimitUpEcologyService limitUpEcologyService;
    private final TradingCalendarService tradingCalendarService;
    private final ObjectMapper objectMapper;

    /** 生成 T 日次日候选清单并落库。 */
    @Transactional
    public Map<String, Object> generateCandidates(LocalDate tradeDate, int topN) {
        int n = Math.max(1, Math.min(topN > 0 ? topN : DEFAULT_TOP_N, 50));
        LocalDate rangeStart = tradeDate.minusDays(LOOKBACK_DAYS);

        // 区间行情（单次批量），按股票分组
        Map<String, List<MarketData>> byStock = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate)
                .stream()
                .collect(Collectors.groupingBy(
                        m -> m.getStockCode() + "|" + m.getExchangeType(), LinkedHashMap::new, Collectors.toList()));
        Map<String, MarketData> todayByKey = marketDataRepository.findByTradeDate(tradeDate).stream()
                .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));
        Map<String, StockInfo> infoByKey = stockInfoRepository.findAll().stream()
                .collect(Collectors.toMap(s -> s.getStockCode() + "|" + s.getExchangeType(), Function.identity(), (a, b) -> a));

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map.Entry<String, MarketData> e : todayByKey.entrySet()) {
            String key = e.getKey();
            MarketData today = e.getValue();
            List<MarketData> stockBars = byStock.getOrDefault(key, List.of());
            if (stockBars.size() < 30) {
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
                    tradeDate, MIN_LISTING_DAYS, stockBars)) {
                continue;
            }

            Map<String, Object> lu = limitUpEcologyService.stockLimitUpFeatures(stockBars, today, name);
            double[] ind = shortIndicators(stockBars);
            double score = scoreT1(lu, ind);

            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("stock_code", code);
            cand.put("exchange_type", exchange);
            cand.put("stock_name", name != null ? name : code);
            cand.put("score", round4(score));
            cand.put("score_type", "rule_score");
            cand.put("is_probability", false);
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
            scored.add(cand);
        }

        scored.sort(Comparator.comparingDouble((Map<String, Object> c) -> (double) c.get("score")).reversed());
        List<Map<String, Object>> top = new ArrayList<>(scored.subList(0, Math.min(n, scored.size())));

        T1Candidate rec = t1CandidateRepository.findByTradeDate(tradeDate).orElseGet(() -> {
            T1Candidate c = new T1Candidate();
            c.setTradeDate(tradeDate);
            return c;
        });
        rec.setTopN(n);
        rec.setCandidatesJson(toJson(top));
        rec.setSettled(Boolean.FALSE);
        rec.setSettledJson(null);
        rec.setEcologyJson(toJson(limitUpEcologyService.ecology(tradeDate, 30)));
        t1CandidateRepository.save(rec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("top_n", top.size());
        out.put("total_scored", scored.size());
        out.put("candidates", top);
        out.put("settled", false);
        out.put("note", "T+1 次日候选：涨停基因（涨停/连板/量比/换手）+ 短期动量 + 量能 + 趋势位置，"
                + "rule_score 非概率；点击「次日核销」在 T+1 结束后回填表现");
        return out;
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
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
