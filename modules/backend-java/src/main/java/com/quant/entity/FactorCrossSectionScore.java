package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一引擎的全截面分数（研究用，落库默认关闭）。
 *
 * <p>存在的唯一理由：<b>Rank IC 是横截面统计量，需要当日全部候选的分数</b>。
 * 而 {@link SelectorResultItem} 只落 Top-N（{@code UnifiedScoreReadService.projectStrategy}
 * 末尾按 topN 切片），只有 Top-N 时算出的 IC 只反映 Top-N 内部排序——
 * 而实测该处名次 IC ≈ 0（T+1 为 0.017~0.028，IC 标准差 0.22）。
 *
 * <p>因此本表与"展示产物"分离：{@code selector_result_item} 服务前端 5 个 tab，
 * 本表服务 {@code FactorResearchEngine} 的 IC 计算。
 *
 * <p>体量：单日候选约 5,500 × 5 策略 ≈ 27,500 行；一年 238 日 ≈ 654 万行量级，
 * 故写入必须显式开启（见 {@code UnifiedScoreReadService} 的 {@code includeCrossSection} 参数）。
 *
 * @see com.quant.service.backtest.FactorResearchEngine#spearmanRankCorrelation
 */
@Data
@Entity
@Table(
        name = "factor_cross_section_score",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_css_day_strategy_stock",
                        columnNames = {"tradeDate", "strategyCode", "stockCode"}
                )
        },
        indexes = {
                @Index(name = "idx_css_lookup", columnList = "tradeDate,strategyCode"),
                @Index(name = "idx_css_version", columnList = "factorVersion,tradeDate")
        }
)
public class FactorCrossSectionScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 信号日 t；引擎只用 <= t 的 bar 计算。 */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 策略因子版本，如 {@code unified_factor_classic_v1}。 */
    @Column(name = "factor_version", nullable = false, length = 64)
    private String factorVersion;

    /** 统一引擎策略 code，如 {@code CLASSIC_SELECTOR}。 */
    @Column(name = "strategy_code", nullable = false, length = 32)
    private String strategyCode;

    /** 旧口径策略名，与 {@code selector_result.strategy_type} 对齐，便于两端对账。 */
    @Column(name = "legacy_strategy_type", nullable = false, length = 32)
    private String legacyStrategyType;

    /** 冗余存储股票代码，使本表可独立查询（不依赖 item 表存活）。 */
    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "exchange_type", length = 16)
    private String exchangeType;

    @Column(name = "stock_name", length = 64)
    private String stockName;

    /**
     * 合成原始分：{@code Σ(w·pct) / Σ|w|}，仅对可用因子求和。
     *
     * <p>对应 {@code StockScore.factorScore()}。注意这是<b>百分位加权和</b>，
     * 不是收益预测值。
     */
    @Column(name = "raw_score")
    private Double rawScore;

    /**
     * 最终分数：{@code rawScore} 在合格集内的百分位 [0,100]。
     *
     * <p>对应 {@code StockScore.finalScore()}；未参与排名时为 {@code null}。
     */
    @Column(name = "final_score")
    private Double finalScore;

    /** 在<b>全截面合格集</b>内的名次，1 起（非 Top-N 名次）。 */
    @Column(name = "cross_section_rank")
    private Integer crossSectionRank;

    /** 可用权重绝对值之和 / 全部权重绝对值之和，衡量数据完整度。 */
    @Column(name = "available_weight_ratio")
    private Double availableWeightRatio;

    /** 是否通过资格闸；不通过者分数为 null，且必须落库（否则 IC 样本被隐式筛选）。 */
    @Column(name = "eligible", nullable = false)
    private Boolean eligible = Boolean.FALSE;

    @Column(name = "reject_reason", length = 128)
    private String rejectReason;

    /**
     * 各因子的 L2 横截面百分位，形如 {@code {"MOM_20": 87.3, ...}}，键为
     * {@code FactorId.name()}。
     *
     * <p>存在的理由：合成分数只能回答"整个策略有没有效"，无法回答"**哪个因子**在拖累"。
     * 逐因子 Rank IC 需要每个因子各自的横截面取值，故随全截面一并落库。
     *
     * <p>{@code null} 语义 = **该行未记录逐因子明细**（V33 之前的旧行），
     * 而不是"该因子不可用"——不可用因子根本不出现在 JSON 里。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factor_percentiles", columnDefinition = "json")
    private Map<String, Double> factorPercentiles;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
