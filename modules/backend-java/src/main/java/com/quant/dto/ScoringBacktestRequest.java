package com.quant.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@Data
public class ScoringBacktestRequest {
    /** 策略类型（与 selector_result.strategyType 一致） */
    private String strategyType;

    /** 回测起始日期（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 回测结束日期（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /** 每日选取的 top N 只股票，默认 10 */
    private Integer topN = 10;

    /** 持仓天数列表（交易日），默认 [1, 3, 5, 10, 20] */
    private List<Integer> holdingDays = List.of(1, 3, 5, 10, 20);
}
