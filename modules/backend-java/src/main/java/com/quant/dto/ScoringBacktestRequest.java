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

    /** 买入佣金率，默认万三 */
    private Double commissionRate = 0.0003;

    /** 卖出印花税率，默认万五；券商或规则变化时由调用方覆盖 */
    private Double sellTaxRate = 0.0005;

    /** 单边滑点率，默认万二 */
    private Double slippageRate = 0.0002;

    /** 因子分组数（截面 IC / 分组收益研究），默认 5 */
    private Integer factorGroupCount = 5;

    /** 因子研究使用的持有期（交易日）；未指定时优先 T+5，否则取 holdingDays 首项 */
    private Integer factorAnalysisHoldingDay;
}
