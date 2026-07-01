package com.quant.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class StrategyBacktestResponse {
    private String strategyType;
    private String stockCode;
    private String exchangeType;
    private String stockName;
    private String startDate;
    private String endDate;
    private BigDecimal initialCapital;
    private BigDecimal finalCapital;
    private Double totalReturn;
    private Double maxDrawdown;
    private Double sharpeRatio;
    private Integer totalTrades;
    private Integer winningTrades;
    private Double winRate;
    private List<Map<String, Object>> trades;
    private List<Map<String, Object>> equityCurve;
}
