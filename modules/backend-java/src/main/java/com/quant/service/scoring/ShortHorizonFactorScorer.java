package com.quant.service.scoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3～5 交易日短周期因子：短期动量、超卖修复、MACD 柱拐头、量能确认。
 * 设计目标：与 T+3/T+5 forward return 的相关性高于中长期 trend 因子。
 */
public final class ShortHorizonFactorScorer {

    public record ShortHorizonInputs(
            double ret3,
            double ret5,
            double rsi14,
            double rsiDelta3,
            double volumeRatio5,
            double macdHistDelta,
            double closeVsMa5Pct
    ) {}

    public record ShortHorizonResult(
            double score,
            ShortHorizonInputs inputs,
            Map<String, Object> breakdown
    ) {}

    private ShortHorizonFactorScorer() {}

    public static ShortHorizonResult score(List<Double> close, List<Double> amount, double[] closeArr) {
        int n = close.size();
        if (n < 22 || closeArr.length < 22) {
            return empty();
        }
        double ret3 = ScoringUtils.pct(close.get(n - 1), close.get(n - 4));
        double ret5 = ScoringUtils.pct(close.get(n - 1), close.get(n - 6));
        double[] rsiArr = TechnicalIndicators.rsi(closeArr, ScoringConstants.RSI_PERIOD);
        double rsi14 = rsiArr[closeArr.length - 1];
        double rsiDelta3 = rsi14 - rsiArr[Math.max(0, closeArr.length - 4)];

        double avgAmount5 = TechnicalIndicators.avg(amount, amount.size() - 5, amount.size() - 1);
        double lastAmount = amount.get(amount.size() - 1);
        double volumeRatio5 = avgAmount5 > 0 ? lastAmount / avgAmount5 : 0;

        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(closeArr);
        int L = closeArr.length - 1;
        double macdHist = macd.macd()[L];
        double macdHistPrev = macd.macd()[Math.max(0, L - 3)];
        double macdHistDelta = macdHist - macdHistPrev;

        double ma5 = TechnicalIndicators.avg(close, n - 5, n - 1);
        double closeVsMa5Pct = ma5 > 0 ? (close.get(n - 1) - ma5) / ma5 * 100 : 0;

        ShortHorizonInputs inputs = new ShortHorizonInputs(
                ret3, ret5, rsi14, rsiDelta3, volumeRatio5, macdHistDelta, closeVsMa5Pct);

        double momentum35 = scoreShortMomentum(ret3, ret5);
        double repair35 = scoreOversoldRepair(rsi14, rsiDelta3, closeVsMa5Pct);
        double macdTurn30 = scoreMacdTurn(macdHist, macdHistDelta);
        double raw = momentum35 + repair35 + macdTurn30;
        double volumeBoost = ScoringUtils.clamp((volumeRatio5 - 0.8) * 15, 0, 10);
        raw += volumeBoost;

        double score = ScoringUtils.clamp(raw, 0, 100);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("ret3_pct", ScoringUtils.round2(ret3));
        breakdown.put("ret5_pct", ScoringUtils.round2(ret5));
        breakdown.put("rsi14", ScoringUtils.round2(rsi14));
        breakdown.put("rsi_delta3", ScoringUtils.round2(rsiDelta3));
        breakdown.put("volume_ratio5", ScoringUtils.round2(volumeRatio5));
        breakdown.put("macd_hist_delta3", ScoringUtils.round2(macdHistDelta));
        breakdown.put("close_vs_ma5_pct", ScoringUtils.round2(closeVsMa5Pct));
        breakdown.put("momentum_component", ScoringUtils.round2(momentum35));
        breakdown.put("repair_component", ScoringUtils.round2(repair35));
        breakdown.put("macd_turn_component", ScoringUtils.round2(macdTurn30));
        breakdown.put("volume_boost", ScoringUtils.round2(volumeBoost));

        return new ShortHorizonResult(score, inputs, breakdown);
    }

    /** 短期动量：温和正收益或浅跌后企稳，惩罚极端追涨 */
    static double scoreShortMomentum(double ret3, double ret5) {
        double s = 0;
        s += ScoringUtils.clamp(ret3 + 2, 0, 12);
        s += ScoringUtils.clamp(ret5 + 3, 0, 12);
        if (ret3 > 8 || ret5 > 12) {
            s -= ScoringUtils.clamp((ret3 - 8) * 0.8 + (ret5 - 12) * 0.5, 0, 11);
        }
        return ScoringUtils.clamp(s, 0, 35);
    }

    /** 超卖修复：RSI 低位且回升，或贴近 MA5 企稳 */
    static double scoreOversoldRepair(double rsi14, double rsiDelta3, double closeVsMa5Pct) {
        double s = 0;
        if (rsi14 < 35) {
            s += ScoringUtils.clamp((35 - rsi14) * 0.8, 0, 18);
        } else if (rsi14 < 45) {
            s += ScoringUtils.clamp((45 - rsi14) * 0.4, 0, 8);
        }
        // 仅当未到"极端超买"（rsi14<80）时，RSI 回升才算"超卖修复"。
        // 极端超买（如横盘后直拉、rsi 冲到 ~100）继续走高属追涨，与超卖修复语义相反，
        // 且 scoreShortMomentum 对极端涨幅已扣分，此处不应再叠加奖励（正常修复可站上 70）。
        if (rsiDelta3 > 0 && rsi14 < 80) {
            s += ScoringUtils.clamp(rsiDelta3 * 1.2, 0, 12);
        }
        if (closeVsMa5Pct >= -3 && closeVsMa5Pct <= 2) {
            s += 5;
        }
        return ScoringUtils.clamp(s, 0, 35);
    }

    /** MACD 柱由负转正或负区收窄 */
    static double scoreMacdTurn(double macdHist, double macdHistDelta) {
        double s = 0;
        if (macdHistDelta > 0) {
            s += ScoringUtils.clamp(macdHistDelta * 5000, 0, 18);
        }
        if (macdHist < 0 && macdHistDelta > 0) {
            s += 8;
        }
        if (macdHist > 0) {
            s += ScoringUtils.clamp(macdHist * 3000, 0, 4);
        }
        return ScoringUtils.clamp(s, 0, 30);
    }

    static boolean passesShortHorizonGate(double shortHorizonScore, double avgAmount20, double minAvgAmount) {
        return avgAmount20 >= minAvgAmount && shortHorizonScore >= 35;
    }

    private static ShortHorizonResult empty() {
        return new ShortHorizonResult(0, new ShortHorizonInputs(0, 0, 50, 0, 0, 0, 0), Map.of());
    }
}
