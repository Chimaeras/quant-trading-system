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

    @Column(nullable = false)
    private Double ret20;

    @Column(nullable = false)
    private Double ret60;

    @Column(nullable = false)
    private Double volatility20;

    @Column(nullable = false)
    private Double maxDrawdown60;

    @Column(nullable = false)
    private Double avgAmount20;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> reasons;

    /** 统一入口下的策略特有字段（例如 score_support、macd_status 等）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> extraMetrics;
}
