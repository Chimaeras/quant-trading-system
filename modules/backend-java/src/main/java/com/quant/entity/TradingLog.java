package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "trading_log")
public class TradingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, length = 32)
    private String orderId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "exchange_type", nullable = false, length = 20)
    private String exchangeType;

    @Column(name = "trade_type", nullable = false)
    private Integer tradeType;

    @Column(name = "trade_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal tradePrice;

    @Column(name = "trade_volume", nullable = false)
    private Integer tradeVolume;

    @Column(name = "trade_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal tradeAmount;

    private BigDecimal commission = BigDecimal.ZERO;

    @Column(name = "stamp_duty")
    private BigDecimal stampDuty = BigDecimal.ZERO;

    @Column(name = "order_type", columnDefinition = "TINYINT DEFAULT 1")
    private Integer orderType = 1;

    @Column(name = "order_time")
    private LocalDateTime orderTime;

    @Column(name = "trade_time")
    private LocalDateTime tradeTime;

    @Column(name = "order_status", columnDefinition = "TINYINT DEFAULT 3")
    private Integer orderStatus = 3;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (orderTime == null) {
            orderTime = LocalDateTime.now();
        }
        if (tradeTime == null) {
            tradeTime = orderTime;
        }
    }
}
