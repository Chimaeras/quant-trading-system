package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(
        name = "selector_result",
        indexes = {
                @Index(name = "idx_selector_result_strategy_trade", columnList = "strategyType,tradeDate"),
                @Index(name = "idx_selector_result_fingerprint", columnList = "requestFingerprint"),
                @Index(name = "idx_selector_result_run_status", columnList = "runStatus")
        }
)
public class SelectorResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String resultId;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Integer lookbackDays = 60;

    @Column(nullable = false)
    private Integer topN = 10;

    @Column(nullable = false)
    private Double minAvgAmount = 30000000.0;

    @Column(nullable = false)
    private Double minTrendScore = 40.0;

    @Column(length = 64)
    private String templateId;

    @Column(length = 32)
    private String factorVersion = "v1";

    /** 统一入口策略类型（TREND/COMPREHENSIVE/MULTI_DIM/CLASSIC_SELECTOR/OVERSOLD_REBOUND/GEOPOLICY_NEWS） */
    @Column(length = 32)
    private String strategyType;

    /** 参数指纹：用于同参复用/断点续跑。 */
    @Column(length = 64)
    private String requestFingerprint;

    /** 执行状态：RUNNING/SUCCESS/PARTIAL/FAILED/CANCELLED。 */
    @Column(length = 20)
    private String runStatus = "RUNNING";

    /** 候选总数。 */
    private Integer totalCandidates = 0;

    /** 已处理数。 */
    private Integer processedCandidates = 0;

    /** 成功数。 */
    private Integer successCandidates = 0;

    /** 失败数。 */
    private Integer failedCandidates = 0;

    /** 最近处理游标（可选），用于排障。 */
    @Column(length = 64)
    private String lastProcessedKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> factorSnapshot;

    /** 因子信息系数（Spearman IC） */
    @Column(precision = 10, scale = 6)
    private Double icValue;

    /** 因子信息比率 */
    @Column(precision = 10, scale = 6)
    private Double irValue;

    /** 夏普贡献度（预留） */
    @Column(precision = 10, scale = 6)
    private Double sharpeContribution;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
