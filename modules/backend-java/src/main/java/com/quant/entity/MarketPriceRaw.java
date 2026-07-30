package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 不复权原始行情（QP-003）。
 *
 * <p>与 {@link MarketData}（前复权）分离：原始成交价专供成交仿真，
 * 避免被后续除权事件回写改写；复权因子见 {@link AdjustFactor}。
 * 双写并存阶段：market_data（前复权）保留供现有选股/回测，本表供新研究模块。
 *
 * <p>口径：OHLCV/成交额/换手率均为不复权原始值；source 记录数据来源（如 eastmoney_fqt0）。
 */
@Data
@Entity
@Table(name = "market_price_raw", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "trade_date"})
})
public class MarketPriceRaw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(precision = 12, scale = 4)
    private BigDecimal openPrice;

    @Column(precision = 12, scale = 4)
    private BigDecimal highPrice;

    @Column(precision = 12, scale = 4)
    private BigDecimal lowPrice;

    @Column(precision = 12, scale = 4)
    private BigDecimal closePrice;

    @Column(precision = 12, scale = 4)
    private BigDecimal preClose;

    @Column
    private Long volume;

    @Column(precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(precision = 8, scale = 4)
    private BigDecimal turnoverRate;

    /** 数据来源标识，如 eastmoney_fqt0；便于追溯与质量审计。 */
    @Column(length = 40)
    private String source;

    /** 数据入库时间，用于 lineage。 */
    @Column(updatable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    protected void onCreate() {
        ingestedAt = LocalDateTime.now();
    }
}
