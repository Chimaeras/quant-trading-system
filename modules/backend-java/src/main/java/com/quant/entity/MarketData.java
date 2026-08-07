package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "market_data", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "trade_date"})
})
public class MarketData {

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

    @Column(precision = 8, scale = 4)
    private BigDecimal changePct;

    @Column
    private Long volume;

    @Column(precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(precision = 8, scale = 4)
    private BigDecimal turnoverRate;

    @Column(precision = 12, scale = 4)
    private BigDecimal peRatio;

    @Column(precision = 12, scale = 4)
    private BigDecimal pbRatio;

    /** 总市值（元）；入库时可由成交额/换手率推算，供 ln(MV) 中性化优先使用。 */
    @Column(precision = 24, scale = 4)
    private BigDecimal totalMarketValue;

    /**
     * 市值口径：TOTAL / FLOAT / FLOAT_CAP_APPROX（MP-025）。
     * amount/turnover 推算值应标 FLOAT_CAP_APPROX，不得冒充 TOTAL。
     */
    @Column(length = 40)
    private String marketCapType;

    /** 市值来源：amount_div_turnover / official / … */
    @Column(length = 60)
    private String marketCapSource;

    /**
     * 可靠性：OFFICIAL / FLOAT_CAP_APPROX / UNKNOWN_LEGACY（MP-025）。
     * CrossSectionNeutralizer 对 UNKNOWN_LEGACY / FLOAT_CAP_APPROX 跳过市值中性化。
     */
    @Column(length = 40)
    private String marketCapReliability;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
