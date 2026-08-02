package com.quant.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StrategyBacktestRequest {
    private String strategyType = "MA_CROSS";
    private String stockCode;
    private String exchangeType = "SZSE";
    private String startDate;
    private String endDate;
    private BigDecimal initialCapital = BigDecimal.valueOf(100000);
    private Integer shortPeriod = 5;
    private Integer longPeriod = 20;
    private Integer bollPeriod = 20;
    private Double bollStdDev = 2.0;
    private Integer macdFast = 12;
    private Integer macdSlow = 26;
}
