package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "position_info")
public class PositionInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 仿真会话ID：0=在线账户；>0=历史演练会话（V2-MP-021，与在线账户隔离）。 */
    @Column(name = "session_id", nullable = false)
    private Long sessionId = 0L;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "exchange_type", nullable = false, length = 20)
    private String exchangeType;

    private Integer volume = 0;

    @Column(name = "available_volume")
    private Integer availableVolume = 0;

    @Column(name = "avg_cost", precision = 12, scale = 4)
    private BigDecimal avgCost = BigDecimal.ZERO;

    @Column(name = "current_price", precision = 12, scale = 4)
    private BigDecimal currentPrice = BigDecimal.ZERO;

    @Column(name = "market_value", precision = 20, scale = 4)
    private BigDecimal marketValue = BigDecimal.ZERO;

    @Column(name = "profit_loss", precision = 20, scale = 4)
    private BigDecimal profitLoss = BigDecimal.ZERO;

    @Column(name = "profit_rate", precision = 8, scale = 4)
    private BigDecimal profitRate = BigDecimal.ZERO;

    @Column(name = "first_buy_date")
    private LocalDate firstBuyDate;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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
