package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "order_type")
    private Integer orderType = 1;

    @Column(name = "order_time")
    private LocalDateTime orderTime;

    @Column(name = "trade_time")
    private LocalDateTime tradeTime;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "order_status")
    private Integer orderStatus = 3;

    @Column(name = "account_id")
    private Long accountId;

    /** 仿真会话ID：0=在线账户；>0=历史演练会话（V2-MP-021）。 */
    @Column(name = "session_id", nullable = false)
    private Long sessionId = 0L;

    @Column(name = "user_id")
    private Long userId;

    /** 成交价来源：CLIENT_SPECIFIED / market_price_raw（V2-MP-021 订单契约）。 */
    @Column(name = "price_source", length = 30)
    private String priceSource;

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
