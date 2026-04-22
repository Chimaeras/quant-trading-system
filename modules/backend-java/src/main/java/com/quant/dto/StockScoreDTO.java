package com.quant.dto;

import java.util.Map;

/**
 * 股票打分结果DTO
 * 统一的打分结果返回格式
 */
public class StockScoreDTO {

    private int rankNo;
    private String stockCode;
    private String exchangeType;
    private String stockName;
    private String tradeDate;
    private double totalScore;
    private double compositeScore;

    // 技术面维度
    private double scoreTechnical;
    private double scoreTrend;
    private double scoreBias;
    private double scoreVolume;
    private double scoreSupport;
    private double scoreMacd;
    private double scoreRsi;
    private double scoreKdj;
    private double scoreMa;

    // 技术状态
    private String trendStatus;
    private String macdStatus;
    private String rsiStatus;
    private String kdjStatus;
    private String volumeStatus;
    private String maStatus;

    // 基本面维度
    private double scoreFundamental;
    private double scorePolicy;
    private double scoreRotation;
    private double scoreIndustryCompare;
    private double scoreSentiment;

    // 风险指标
    private double riskPenalty;
    private double ret20;
    private double ret60;
    private double volatility20;
    private double maxDrawdown60;
    private double avgAmount20;

    // 投资建议
    private String buySignal;
    private String suggestion;
    private String factorVersion;

    // 详细原因
    private Map<String, Object> reasons;
    private Map<String, Object> extraMetrics;

    public StockScoreDTO() {}

    // ==================== Builder Pattern ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final StockScoreDTO dto = new StockScoreDTO();

        public Builder rankNo(int rankNo) { dto.rankNo = rankNo; return this; }
        public Builder stockCode(String stockCode) { dto.stockCode = stockCode; return this; }
        public Builder exchangeType(String exchangeType) { dto.exchangeType = exchangeType; return this; }
        public Builder stockName(String stockName) { dto.stockName = stockName; return this; }
        public Builder tradeDate(String tradeDate) { dto.tradeDate = tradeDate; return this; }
        public Builder totalScore(double totalScore) { dto.totalScore = totalScore; return this; }
        public Builder compositeScore(double compositeScore) { dto.compositeScore = compositeScore; return this; }
        public Builder scoreTechnical(double scoreTechnical) { dto.scoreTechnical = scoreTechnical; return this; }
        public Builder scoreTrend(double scoreTrend) { dto.scoreTrend = scoreTrend; return this; }
        public Builder scoreBias(double scoreBias) { dto.scoreBias = scoreBias; return this; }
        public Builder scoreVolume(double scoreVolume) { dto.scoreVolume = scoreVolume; return this; }
        public Builder scoreSupport(double scoreSupport) { dto.scoreSupport = scoreSupport; return this; }
        public Builder scoreMacd(double scoreMacd) { dto.scoreMacd = scoreMacd; return this; }
        public Builder scoreRsi(double scoreRsi) { dto.scoreRsi = scoreRsi; return this; }
        public Builder scoreKdj(double scoreKdj) { dto.scoreKdj = scoreKdj; return this; }
        public Builder scoreMa(double scoreMa) { dto.scoreMa = scoreMa; return this; }
        public Builder trendStatus(String trendStatus) { dto.trendStatus = trendStatus; return this; }
        public Builder macdStatus(String macdStatus) { dto.macdStatus = macdStatus; return this; }
        public Builder rsiStatus(String rsiStatus) { dto.rsiStatus = rsiStatus; return this; }
        public Builder kdjStatus(String kdjStatus) { dto.kdjStatus = kdjStatus; return this; }
        public Builder volumeStatus(String volumeStatus) { dto.volumeStatus = volumeStatus; return this; }
        public Builder maStatus(String maStatus) { dto.maStatus = maStatus; return this; }
        public Builder scoreFundamental(double scoreFundamental) { dto.scoreFundamental = scoreFundamental; return this; }
        public Builder scorePolicy(double scorePolicy) { dto.scorePolicy = scorePolicy; return this; }
        public Builder scoreRotation(double scoreRotation) { dto.scoreRotation = scoreRotation; return this; }
        public Builder scoreIndustryCompare(double scoreIndustryCompare) { dto.scoreIndustryCompare = scoreIndustryCompare; return this; }
        public Builder scoreSentiment(double scoreSentiment) { dto.scoreSentiment = scoreSentiment; return this; }
        public Builder riskPenalty(double riskPenalty) { dto.riskPenalty = riskPenalty; return this; }
        public Builder ret20(double ret20) { dto.ret20 = ret20; return this; }
        public Builder ret60(double ret60) { dto.ret60 = ret60; return this; }
        public Builder volatility20(double volatility20) { dto.volatility20 = volatility20; return this; }
        public Builder maxDrawdown60(double maxDrawdown60) { dto.maxDrawdown60 = maxDrawdown60; return this; }
        public Builder avgAmount20(double avgAmount20) { dto.avgAmount20 = avgAmount20; return this; }
        public Builder buySignal(String buySignal) { dto.buySignal = buySignal; return this; }
        public Builder suggestion(String suggestion) { dto.suggestion = suggestion; return this; }
        public Builder factorVersion(String factorVersion) { dto.factorVersion = factorVersion; return this; }
        public Builder reasons(Map<String, Object> reasons) { dto.reasons = reasons; return this; }
        public Builder extraMetrics(Map<String, Object> extraMetrics) { dto.extraMetrics = extraMetrics; return this; }

        public StockScoreDTO build() {
            return dto;
        }
    }

    // ==================== Getters and Setters ====================

    public int getRankNo() { return rankNo; }
    public void setRankNo(int rankNo) { this.rankNo = rankNo; }
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public String getExchangeType() { return exchangeType; }
    public void setExchangeType(String exchangeType) { this.exchangeType = exchangeType; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getTradeDate() { return tradeDate; }
    public void setTradeDate(String tradeDate) { this.tradeDate = tradeDate; }
    public double getTotalScore() { return totalScore; }
    public void setTotalScore(double totalScore) { this.totalScore = totalScore; }
    public double getCompositeScore() { return compositeScore; }
    public void setCompositeScore(double compositeScore) { this.compositeScore = compositeScore; }
    public double getScoreTechnical() { return scoreTechnical; }
    public void setScoreTechnical(double scoreTechnical) { this.scoreTechnical = scoreTechnical; }
    public double getScoreTrend() { return scoreTrend; }
    public void setScoreTrend(double scoreTrend) { this.scoreTrend = scoreTrend; }
    public double getScoreBias() { return scoreBias; }
    public void setScoreBias(double scoreBias) { this.scoreBias = scoreBias; }
    public double getScoreVolume() { return scoreVolume; }
    public void setScoreVolume(double scoreVolume) { this.scoreVolume = scoreVolume; }
    public double getScoreSupport() { return scoreSupport; }
    public void setScoreSupport(double scoreSupport) { this.scoreSupport = scoreSupport; }
    public double getScoreMacd() { return scoreMacd; }
    public void setScoreMacd(double scoreMacd) { this.scoreMacd = scoreMacd; }
    public double getScoreRsi() { return scoreRsi; }
    public void setScoreRsi(double scoreRsi) { this.scoreRsi = scoreRsi; }
    public double getScoreKdj() { return scoreKdj; }
    public void setScoreKdj(double scoreKdj) { this.scoreKdj = scoreKdj; }
    public double getScoreMa() { return scoreMa; }
    public void setScoreMa(double scoreMa) { this.scoreMa = scoreMa; }
    public String getTrendStatus() { return trendStatus; }
    public void setTrendStatus(String trendStatus) { this.trendStatus = trendStatus; }
    public String getMacdStatus() { return macdStatus; }
    public void setMacdStatus(String macdStatus) { this.macdStatus = macdStatus; }
    public String getRsiStatus() { return rsiStatus; }
    public void setRsiStatus(String rsiStatus) { this.rsiStatus = rsiStatus; }
    public String getKdjStatus() { return kdjStatus; }
    public void setKdjStatus(String kdjStatus) { this.kdjStatus = kdjStatus; }
    public String getVolumeStatus() { return volumeStatus; }
    public void setVolumeStatus(String volumeStatus) { this.volumeStatus = volumeStatus; }
    public String getMaStatus() { return maStatus; }
    public void setMaStatus(String maStatus) { this.maStatus = maStatus; }
    public double getScoreFundamental() { return scoreFundamental; }
    public void setScoreFundamental(double scoreFundamental) { this.scoreFundamental = scoreFundamental; }
    public double getScorePolicy() { return scorePolicy; }
    public void setScorePolicy(double scorePolicy) { this.scorePolicy = scorePolicy; }
    public double getScoreRotation() { return scoreRotation; }
    public void setScoreRotation(double scoreRotation) { this.scoreRotation = scoreRotation; }
    public double getScoreIndustryCompare() { return scoreIndustryCompare; }
    public void setScoreIndustryCompare(double scoreIndustryCompare) { this.scoreIndustryCompare = scoreIndustryCompare; }
    public double getScoreSentiment() { return scoreSentiment; }
    public void setScoreSentiment(double scoreSentiment) { this.scoreSentiment = scoreSentiment; }
    public double getRiskPenalty() { return riskPenalty; }
    public void setRiskPenalty(double riskPenalty) { this.riskPenalty = riskPenalty; }
    public double getRet20() { return ret20; }
    public void setRet20(double ret20) { this.ret20 = ret20; }
    public double getRet60() { return ret60; }
    public void setRet60(double ret60) { this.ret60 = ret60; }
    public double getVolatility20() { return volatility20; }
    public void setVolatility20(double volatility20) { this.volatility20 = volatility20; }
    public double getMaxDrawdown60() { return maxDrawdown60; }
    public void setMaxDrawdown60(double maxDrawdown60) { this.maxDrawdown60 = maxDrawdown60; }
    public double getAvgAmount20() { return avgAmount20; }
    public void setAvgAmount20(double avgAmount20) { this.avgAmount20 = avgAmount20; }
    public String getBuySignal() { return buySignal; }
    public void setBuySignal(String buySignal) { this.buySignal = buySignal; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    public String getFactorVersion() { return factorVersion; }
    public void setFactorVersion(String factorVersion) { this.factorVersion = factorVersion; }
    public Map<String, Object> getReasons() { return reasons; }
    public void setReasons(Map<String, Object> reasons) { this.reasons = reasons; }
    public Map<String, Object> getExtraMetrics() { return extraMetrics; }
    public void setExtraMetrics(Map<String, Object> extraMetrics) { this.extraMetrics = extraMetrics; }
}
