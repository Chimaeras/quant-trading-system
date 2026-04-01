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
