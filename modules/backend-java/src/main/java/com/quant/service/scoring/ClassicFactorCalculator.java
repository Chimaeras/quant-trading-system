package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.service.scoring.model.CandidateScore;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/**
 * 经典选股因子计算器：趋势40% + 动量25% + 量价20% - 风险15%。
 */
@Component
public class ClassicFactorCalculator {

    public static final String FACTOR_VERSION = "classic_v3_short_horizon";
    public static final String HORIZON_MEDIUM = StrategyFormulaRegistry.HORIZON_MEDIUM_TREND;
    public static final String HORIZON_SHORT = StrategyFormulaRegistry.HORIZON_SHORT_3_5D;
    private static final int MIN_BARS = 70;

    /** 权重优先从 YAML 读取，缺失时使用硬编码默认值 */
    private static double weightTrend() {
        var p = ScoringPropertiesBridge.get();
        return p != null && p.getClassicFactor() != null ? p.getClassicFactor().getTrendWeight() : 0.35;
    }
    private static double weightMomentum() {
        var p = ScoringPropertiesBridge.get();
        return p != null && p.getClassicFactor() != null ? p.getClassicFactor().getMomentumWeight() : 0.20;
    }
    private static double weightVolume() {
        var p = ScoringPropertiesBridge.get();
        return p != null && p.getClassicFactor() != null ? p.getClassicFactor().getVolumeWeight() : 0.20;
    }
    private static double weightRiskPenalty() {
        var p = ScoringPropertiesBridge.get();
        return p != null && p.getClassicFactor() != null ? p.getClassicFactor().getRiskPenaltyWeight() : 0.25;
    }

    public Optional<CandidateScore> calculate(
            String stockCode,
            String exchangeType,
            List<MarketData> bars,
            double minAvgAmount,
            double minTrendScore,
            Function<String, String> nameResolver,
            boolean excludeST
    ) {
        return calculate(stockCode, exchangeType, bars, minAvgAmount, minTrendScore,
                nameResolver, excludeST, HORIZON_MEDIUM, null, null, null, 0);
    }

    public Optional<CandidateScore> calculate(
            String stockCode,
            String exchangeType,
            List<MarketData> bars,
            double minAvgAmount,
            double minTrendScore,
            Function<String, String> nameResolver,
            boolean excludeST,
            String horizonProfile
    ) {
        return calculate(stockCode, exchangeType, bars, minAvgAmount, minTrendScore,
                nameResolver, excludeST, horizonProfile, null, null, null, 0);
    }

    public Optional<CandidateScore> calculate(
            String stockCode,
            String exchangeType,
            List<MarketData> bars,
            double minAvgAmount,
            double minTrendScore,
            Function<String, String> nameResolver,
            boolean excludeST,
            String horizonProfile,
            LocalDate tradeDate
    ) {
        return calculate(stockCode, exchangeType, bars, minAvgAmount, minTrendScore,
                nameResolver, excludeST, horizonProfile, tradeDate, null, null, 0);
    }

    public Optional<CandidateScore> calculate(
            String stockCode,
            String exchangeType,
            List<MarketData> bars,
            double minAvgAmount,
            double minTrendScore,
            Function<String, String> nameResolver,
            boolean excludeST,
            String horizonProfile,
            LocalDate tradeDate,
            Integer stockStatus
    ) {
        return calculate(stockCode, exchangeType, bars, minAvgAmount, minTrendScore,
                nameResolver, excludeST, horizonProfile, tradeDate, stockStatus, null, 0);
    }

    public Optional<CandidateScore> calculate(
            String stockCode,
            String exchangeType,
            List<MarketData> bars,
            double minAvgAmount,
            double minTrendScore,
            Function<String, String> nameResolver,
            boolean excludeST,
            String horizonProfile,
            LocalDate tradeDate,
            Integer stockStatus,
            LocalDate listDate,
            int minListingDays
    ) {
        if (bars == null || bars.size() < MIN_BARS) {
            return Optional.empty();
        }
        if (!ScoringUtils.isSignalBarOnTradeDate(bars, tradeDate)) {
            return Optional.empty();
        }

        List<Double> close = new ArrayList<>();
        List<Double> amount = new ArrayList<>();
        for (MarketData b : bars) {
            if (b.getClosePrice() == null || b.getClosePrice().doubleValue() <= 0) continue;
            close.add(b.getClosePrice().doubleValue());
            amount.add(b.getAmount() != null ? b.getAmount().doubleValue() : 0d);
        }
        if (close.size() < MIN_BARS) {
            return Optional.empty();
        }

        String cacheKey = stockCode + ":" + exchangeType;
        String name = nameResolver != null ? nameResolver.apply(cacheKey) : stockCode;
        if (!ScoringUtils.hasMinListingTenure(listDate, tradeDate, minListingDays, bars)) {
            return Optional.empty();
        }
        if (!ScoringUtils.isTradableForScoring(bars, stockCode, exchangeType, name, stockStatus, excludeST, true, true)) {
            return Optional.empty();
        }

        int n = close.size();
        double ret20 = ScoringUtils.pct(close.get(n - 1), close.get(n - 21));
        double ret60 = ScoringUtils.pct(close.get(n - 1), close.get(n - 61));
        double ma20 = TechnicalIndicators.avg(close, n - 20, n - 1);
        double ma60 = TechnicalIndicators.avg(close, n - 60, n - 1);
        double avgAmount20 = TechnicalIndicators.avg(amount, amount.size() - 20, amount.size() - 1);
        double vol20 = ScoringUtils.stdPct(close, 20) * 100;
        double mdd60 = ScoringUtils.maxDrawdown(close, 60);

        double[] closeArr = close.stream().mapToDouble(Double::doubleValue).toArray();
        double rsi14 = TechnicalIndicators.rsiAt(closeArr, closeArr.length - 1, ScoringConstants.RSI_PERIOD);
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(closeArr);
        double macdHist = macd.macd()[closeArr.length - 1];

        double volumeRatio = amount.get(amount.size() - 1) / Math.max(1d, avgAmount20);

        boolean shortProfile = HORIZON_SHORT.equals(horizonProfile);
        ShortHorizonFactorScorer.ShortHorizonResult shortHorizon = shortProfile
                ? ShortHorizonFactorScorer.score(close, amount, closeArr)
                : null;
        double shortHorizonScore = shortHorizon != null ? shortHorizon.score() : 0;

        double trend = 0;
        trend += ma20 > ma60 ? 35 : 0;
        trend += ScoringUtils.clamp(ret20, -10, 25);
        trend += ScoringUtils.clamp(ret60, -15, 35) * 0.5;
        trend = ScoringUtils.clamp(trend, 0, 100);

        double momentum = 0;
        momentum += ScoringUtils.clamp((rsi14 - 40) * 1.5, 0, 35);
        double macdHistRatio = close.get(n - 1) > 0 ? macdHist / close.get(n - 1) : 0;
        momentum += ScoringUtils.clamp(macdHistRatio * 40000, 0, 35);
        momentum += ScoringUtils.clamp(volumeRatio * 12, 0, 30);
        momentum = ScoringUtils.clamp(momentum, 0, 100);

        double volumeScore = ScoringUtils.clamp(
                (avgAmount20 / Math.max(1, minAvgAmount)) * 60 + volumeRatio * 20, 0, 100);
        double riskPenalty = ScoringUtils.clamp(vol20 * 1.5 + mdd60 * 1.2, 0, 100);

        double finalScore;
        if (shortProfile) {
            finalScore = 0.40 * shortHorizonScore + 0.25 * momentum + 0.20 * volumeScore - 0.25 * riskPenalty;
        } else {
            finalScore = weightTrend() * trend + weightMomentum() * momentum
                    + weightVolume() * volumeScore - weightRiskPenalty() * riskPenalty;
        }

        boolean passMinAvgAmount = avgAmount20 >= minAvgAmount;
        boolean passMinTrendScore = shortProfile ? shortHorizonScore >= 35 : trend >= minTrendScore;
        boolean passMaTrend = shortProfile || ma20 > ma60;
        boolean passRet20 = shortProfile || ret20 >= -5;
        if (!passMinAvgAmount || !passMinTrendScore || !passMaTrend || !passRet20) {
            return Optional.empty();
        }

        CandidateScore c = new CandidateScore();
        c.setStockCode(stockCode);
        c.setExchangeType(exchangeType);
        c.setStockName(name != null ? name : stockCode);
        c.setFinalScore(finalScore);
        c.setTrendScore(trend);
        c.setMomentumScore(momentum);
        c.setVolumeScore(volumeScore);
        c.setRiskPenalty(riskPenalty);
        c.setRet20(ret20);
        c.setRet60(ret60);
        c.setVolatility20(vol20);
        c.setMaxDrawdown60(mdd60);
        c.setAvgAmount20(avgAmount20);
        c.setPassMinAvgAmount(passMinAvgAmount);
        c.setPassMinTrendScore(passMinTrendScore);
        c.setPassMaTrend(passMaTrend);
        c.setPassRet20(passRet20);

        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("horizon_profile", shortProfile ? HORIZON_SHORT : HORIZON_MEDIUM);
        if (shortProfile && shortHorizon != null) {
            reasons.put("short_horizon_score", ScoringUtils.round2(shortHorizonScore));
            reasons.putAll(shortHorizon.breakdown());
        }
        reasons.put("trend", ma20 > ma60 ? "MA20 在 MA60 上方" : "趋势偏弱");
        reasons.put("ret20", ScoringUtils.round2(ret20) + "%");
        reasons.put("ret60", ScoringUtils.round2(ret60) + "%");
        reasons.put("rsi14", ScoringUtils.round2(rsi14));
        reasons.put("volume_ratio", ScoringUtils.round2(volumeRatio));
        reasons.put("pass_min_avg_amount", passMinAvgAmount);
        reasons.put("pass_min_trend_score", passMinTrendScore);
        reasons.put("pass_ma20_gt_ma60", passMaTrend);
        reasons.put("pass_ret20_floor", passRet20);
        c.setReasons(reasons);

        return Optional.of(c);
    }

    /**
     * 截面 Z-Score 标准化后重算 finalScore，并输出因子共线性检测日志。
     */
    public void normalizeCrossSection(List<CandidateScore> candidates, double minStd) {
        if (candidates == null || candidates.size() < 2) return;
        zScoreAndRescore(candidates, CandidateScore::getTrendScore, CandidateScore::setTrendScore);
        zScoreAndRescore(candidates, CandidateScore::getMomentumScore, CandidateScore::setMomentumScore);
        zScoreAndRescore(candidates, CandidateScore::getVolumeScore, CandidateScore::setVolumeScore);
        zScoreAndRescore(candidates, CandidateScore::getRiskPenalty, CandidateScore::setRiskPenalty);
        // 因子共线性检测（仅日志告警，不阻断计算）
        FactorCollinearityChecker.logVifReport(candidates);
        for (CandidateScore c : candidates) {
            double oldFinal = c.getFinalScore();
            double oldMacro = c.getMacroAdjustedFinal();
            String profile = c.getReasons() != null
                    ? String.valueOf(c.getReasons().getOrDefault("horizon_profile", HORIZON_MEDIUM))
                    : HORIZON_MEDIUM;
            boolean shortProfile = HORIZON_SHORT.equals(profile);
            double fs;
            if (shortProfile) {
                Object shObj = c.getReasons().get("short_horizon_score");
                double sh = shObj instanceof Number num ? num.doubleValue() : c.getMomentumScore();
                fs = 0.40 * sh + 0.25 * c.getMomentumScore() + 0.20 * c.getVolumeScore() - 0.25 * c.getRiskPenalty();
            } else {
                fs = weightTrend() * c.getTrendScore()
                        + weightMomentum() * c.getMomentumScore()
                        + weightVolume() * c.getVolumeScore()
                        - weightRiskPenalty() * c.getRiskPenalty();
            }
            c.setFinalScore(ScoringUtils.clamp(fs, 0, 100));
            if (oldMacro > 0 && oldFinal > 0) {
                double ratio = c.getFinalScore() / oldFinal;
                c.setMacroAdjustedFinal(ScoringUtils.clamp(oldMacro * ratio, 0, 100));
            }
        }
    }

    private void zScoreAndRescore(
            List<CandidateScore> candidates,
            java.util.function.ToDoubleFunction<CandidateScore> getter,
            java.util.function.BiConsumer<CandidateScore, Double> setter
    ) {
        double[] values = candidates.stream().mapToDouble(getter).toArray();
        double mean = Arrays.stream(values).average().orElse(0);
        double var = 0;
        for (double v : values) {
            var += (v - mean) * (v - mean);
        }
        double std = values.length > 1 ? Math.sqrt(var / values.length) : 0;
        if (std < 1e-6) std = 1e-6;

        for (int i = 0; i < candidates.size(); i++) {
            double z = (values[i] - mean) / std;
            double normalized = ScoringUtils.clamp(50 + z * 15, 0, 100);
            setter.accept(candidates.get(i), normalized);
        }
    }
}
