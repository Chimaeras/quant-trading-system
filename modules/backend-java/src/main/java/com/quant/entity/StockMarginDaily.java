package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 融资余额快照（用于「融资余额增减」轮动子分）。
 */
@Data
@Entity
@Table(name = "stock_margin_daily", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "trade_date"})
})
public class StockMarginDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 融资余额（元或万元，仅用于日环比） */
    @Column(precision = 20, scale = 4)
    private BigDecimal marginBalance;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
