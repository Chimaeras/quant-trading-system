package com.quant.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 回测结果DTO
 * 统一的回测结果返回格式
 */
public class BacktestResultDTO {

    private String resultId;
    private String strategyId;
    private String strategyName;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal initialCapital;
    private BigDecimal finalCapital;
    private BigDecimal totalReturn;
    private BigDecimal annualReturn;
    private BigDecimal sharpeRatio;
    private BigDecimal maxDrawdown;
    private Integer totalTrades;
    private BigDecimal winRate;
    private Double totalCost;
    private List<Map<String, Object>> equityCurve;
    private Map<String, Object> params;
    private Integer runTime;
    private Integer status;
    private String createdAt;

    public BacktestResultDTO() {}

    // ==================== Builder Pattern ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final BacktestResultDTO dto = new BacktestResultDTO();

        public Builder resultId(String resultId) { dto.resultId = resultId; return this; }
        public Builder strategyId(String strategyId) { dto.strategyId = strategyId; return this; }
        public Builder strategyName(String strategyName) { dto.strategyName = strategyName; return this; }
        public Builder startDate(LocalDate startDate) { dto.startDate = startDate; return this; }
        public Builder endDate(LocalDate endDate) { dto.endDate = endDate; return this; }
        public Builder initialCapital(BigDecimal initialCapital) { dto.initialCapital = initialCapital; return this; }
        public Builder finalCapital(BigDecimal finalCapital) { dto.finalCapital = finalCapital; return this; }
        public Builder totalReturn(BigDecimal totalReturn) { dto.totalReturn = totalReturn; return this; }
        public Builder annualReturn(BigDecimal annualReturn) { dto.annualReturn = annualReturn; return this; }
        public Builder sharpeRatio(BigDecimal sharpeRatio) { dto.sharpeRatio = sharpeRatio; return this; }
        public Builder maxDrawdown(BigDecimal maxDrawdown) { dto.maxDrawdown = maxDrawdown; return this; }
        public Builder totalTrades(Integer totalTrades) { dto.totalTrades = totalTrades; return this; }
        public Builder winRate(BigDecimal winRate) { dto.winRate = winRate; return this; }
        public Builder totalCost(Double totalCost) { dto.totalCost = totalCost; return this; }
        public Builder equityCurve(List<Map<String, Object>> equityCurve) { dto.equityCurve = equityCurve; return this; }
        public Builder params(Map<String, Object> params) { dto.params = params; return this; }
        public Builder runTime(Integer runTime) { dto.runTime = runTime; return this; }
        public Builder status(Integer status) { dto.status = status; return this; }
        public Builder createdAt(String createdAt) { dto.createdAt = createdAt; return this; }

        public BacktestResultDTO build() {
            return dto;
        }
    }

    // ==================== Convenience Methods ====================

    /**
     * 检查回测是否盈利
     */
    public boolean isProfitable() {
        return totalReturn != null && totalReturn.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 获取收益率百分比字符串
     */
    public String getTotalReturnPct() {
        return totalReturn != null ? String.format("%.2f%%", totalReturn) : "0.00%";
    }

    // ==================== Getters and Setters ====================

    public String getResultId() { return resultId; }
    public void setResultId(String resultId) { this.resultId = resultId; }
    public String getStrategyId() { return strategyId; }
    public void setStrategyId(String strategyId) { this.strategyId = strategyId; }
    public String getStrategyName() { return strategyName; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public BigDecimal getInitialCapital() { return initialCapital; }
    public void setInitialCapital(BigDecimal initialCapital) { this.initialCapital = initialCapital; }
    public BigDecimal getFinalCapital() { return finalCapital; }
    public void setFinalCapital(BigDecimal finalCapital) { this.finalCapital = finalCapital; }
    public BigDecimal getTotalReturn() { return totalReturn; }
    public void setTotalReturn(BigDecimal totalReturn) { this.totalReturn = totalReturn; }
    public BigDecimal getAnnualReturn() { return annualReturn; }
    public void setAnnualReturn(BigDecimal annualReturn) { this.annualReturn = annualReturn; }
    public BigDecimal getSharpeRatio() { return sharpeRatio; }
    public void setSharpeRatio(BigDecimal sharpeRatio) { this.sharpeRatio = sharpeRatio; }
    public BigDecimal getMaxDrawdown() { return maxDrawdown; }
    public void setMaxDrawdown(BigDecimal maxDrawdown) { this.maxDrawdown = maxDrawdown; }
    public Integer getTotalTrades() { return totalTrades; }
    public void setTotalTrades(Integer totalTrades) { this.totalTrades = totalTrades; }
    public BigDecimal getWinRate() { return winRate; }
    public void setWinRate(BigDecimal winRate) { this.winRate = winRate; }
    public Double getTotalCost() { return totalCost; }
    public void setTotalCost(Double totalCost) { this.totalCost = totalCost; }
    public List<Map<String, Object>> getEquityCurve() { return equityCurve; }
    public void setEquityCurve(List<Map<String, Object>> equityCurve) { this.equityCurve = equityCurve; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public Integer getRunTime() { return runTime; }
    public void setRunTime(Integer runTime) { this.runTime = runTime; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
