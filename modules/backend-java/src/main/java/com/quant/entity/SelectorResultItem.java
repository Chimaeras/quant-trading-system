package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Data
@Entity
@Table(
        name = "selector_result_item",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_selector_result_item_result_stock",
                        columnNames = {"resultId", "stockCode", "exchangeType"}
                )
        },
        indexes = {
                @Index(name = "idx_selector_result_item_result_rank", columnList = "resultId,rankNo"),
                @Index(name = "idx_selector_result_item_result_stock", columnList = "resultId,stockCode,exchangeType")
        }
)
public class SelectorResultItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String resultId;

    @Column(nullable = false)
    private Integer rankNo;

    /** 与父结果一致的策略类型，便于按策略过滤。 */
    @Column(length = 32)
    private String strategyType;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 100)
    private String stockName;

    @Column(nullable = false)
    private Double finalScore;

    @Column(nullable = false)
    private Double trendScore;

    @Column(nullable = false)
    private Double momentumScore;

    @Column(nullable = false)
    private Double volumeScore;

    @Column(nullable = false)
    private Double riskPenalty;

    /**
     * 20 日涨跌幅(%)。NULL 表示「不可用/未计算」（V30 起可表达，见 V30 迁移）。
     * 注意 0 可能是真实值：本库实测有 4 行个股恰好回到 20 个交易日前的价格。
     */
    @Column
    private Double ret20;

    /** 60 日涨跌幅(%)。NULL = 不可用/未计算（V30 起）。 */
    @Column
    private Double ret60;

    /**
     * 20 日波动率(%)。
     * <p>语义警示：{@code OVERSOLD_REBOUND} 策略历史上曾把「加权总分」写入本列
     * （{@code OversoldReboundScoringService}），V30 起已改为写真实波动率；
     * 历史行中该策略的取值仍可能是加权分，读取时须结合 {@code strategyType} 判别。
     */
    @Column
    private Double volatility20;

    /** 60 日最大回撤(%)。NULL = 不可用/未计算（V30 起）。 */
    @Column
    private Double maxDrawdown60;

    /** 20 日日均成交额(万元)。NULL = 不可用/未计算（V25 起）；0 仅当 20 根有效 bar 的均值确为 0。 */
    @Column
    private Double avgAmount20;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> reasons;

    /** 统一入口下的策略特有字段（例如 score_support、macd_status 等）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> extraMetrics;
}
