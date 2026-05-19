package com.quant.service.scoring.model;

import com.quant.entity.SelectorResultItem;
import com.quant.service.scoring.DataQuality;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 经典多因子（趋势+动量+量价-风险）统一评分模型。
 */
@Data
public class CandidateScore {

    private String stockCode;
    private String exchangeType;
    private String stockName;

    private double finalScore;
    private double trendScore;
    private double momentumScore;
    private double volumeScore;
    private double riskPenalty;

    private double ret20;
    private double ret60;
    private double volatility20;
    private double maxDrawdown60;
    private double avgAmount20;

    private Map<String, Object> reasons = new LinkedHashMap<>();
    private DataQuality dataQuality = DataQuality.TECHNICAL_ONLY;

    private boolean passMinAvgAmount;
    private boolean passMinTrendScore;
    private boolean passMaTrend;
    private boolean passRet20;

    // 宏观叠加（Geopolicy 等策略可选）
    private double geoRiskIndex01;
    private double newsSentIndex01;
    private double macroPolicyScore01;
    private double macroAdjustedFinal;

    public boolean isClassicPassed() {
        return passMinAvgAmount && passMinTrendScore && passMaTrend && passRet20;
    }

    public double effectiveFinalScore() {
        return macroAdjustedFinal > 0 ? macroAdjustedFinal : finalScore;
    }

    public void applyMacroOverlay(double policyScore01, double geoRiskIndex01, double newsSentIndex01) {
        applyMacroOverlay(policyScore01, geoRiskIndex01, newsSentIndex01, 1.0);
    }

    public void applyMacroOverlay(double policyScore01, double geoRiskIndex01,
                                 double newsSentIndex01, double industrySensitivity) {
        this.macroPolicyScore01 = policyScore01;
        this.geoRiskIndex01 = geoRiskIndex01;
        this.newsSentIndex01 = newsSentIndex01;
        double policyBoost = (policyScore01 - 0.5) * 20.0;
        double macroRisk = geoRiskIndex01 * 25.0;
        double macroDelta = (policyBoost - macroRisk) * industrySensitivity;
        this.macroAdjustedFinal = Math.max(0, Math.min(100, finalScore + macroDelta));
    }

    public SelectorResultItem toSelectorResultItem(String resultId, int rankNo, String strategyType) {
        SelectorResultItem item = new SelectorResultItem();
        item.setResultId(resultId);
        item.setRankNo(rankNo);
        item.setStrategyType(strategyType);
        item.setStockCode(stockCode);
        item.setExchangeType(exchangeType);
        item.setStockName(stockName != null ? stockName : stockCode);
        item.setFinalScore(round2(effectiveFinalScore()));
        item.setTrendScore(round2(trendScore));
        item.setMomentumScore(round2(momentumScore));
        item.setVolumeScore(round2(volumeScore));
        double macroRiskPenalty = geoRiskIndex01 * 25.0;
        item.setRiskPenalty(round2(Math.max(0, Math.min(100, riskPenalty + macroRiskPenalty))));
        item.setRet20(round2(ret20));
        item.setRet60(round2(ret60));
        item.setVolatility20(round2(volatility20));
        item.setMaxDrawdown60(round2(maxDrawdown60));
        item.setAvgAmount20(round2(avgAmount20));
        item.setReasons(reasons != null ? reasons : Map.of());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("data_quality", dataQuality != null ? dataQuality.name() : DataQuality.TECHNICAL_ONLY.name());
        item.setExtraMetrics(extra);
        return item;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
