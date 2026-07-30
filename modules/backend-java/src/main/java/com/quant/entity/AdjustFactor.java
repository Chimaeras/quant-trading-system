package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 复权因子（QP-003）。
 *
 * <p>定义：前复权价 = 原始价 × 复权因子（factor）。
 * 即 {@code qfq_close = raw_close × factor}，故 {@code factor = qfq_close / raw_close}。
 *
 * <p>当前 source=derived_local：由现有 market_data（前复权）与 market_price_raw（不复权）收盘价
 * 反推而得，可靠度 limited（受现有数据质量与是否已发生后续除权影响）；
 * 后续接入东方财富/Tushare 官方复权因子后替换 source 并提升可靠度。
 *
 * <p>PIT 语义：截至 signal_date 可知的复权因子用于生成连续价格序列，
 * 不直接依赖会回写历史的最新前复权数据（QP-003 目标）。
 */
@Data
@Entity
@Table(name = "adjust_factor", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "trade_date"})
})
public class AdjustFactor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 复权因子：前复权价 = 原始价 × factor。 */
    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal factor;

    /** 来源：derived_local（本地反推）/ eastmoney / tushare。 */
    @Column(nullable = false, length = 40)
    private String source;

    /** 可靠度标记：limited（本地反推）/ official（官方源）。 */
    @Column(length = 20)
    private String reliability;

    @Column(updatable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    protected void onCreate() {
        ingestedAt = LocalDateTime.now();
    }
}
