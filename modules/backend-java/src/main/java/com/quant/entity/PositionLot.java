package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 持仓批次（V2-MP-021）。
 *
 * <p>每次买入生成一条 lot，记录建仓仿真交易日 {@link #acquisitionTradeDate} 与未卖数量
 * {@link #remainingVolume}。A 股 T+1 只依据仿真交易日判定：目标交易日严格晚于
 * acquisitionTradeDate 的批次方可卖出（acquisition < target）。卖出按
 * acquisitionTradeDate 升序 FIFO 消耗批次。禁止用单一 first_buy_date 表示多批持仓。
 */
@Data
@Entity
@Table(name = "position_lot")
public class PositionLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId = 0L;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "exchange_type", nullable = false, length = 20)
    private String exchangeType;

    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;

    @Column(name = "acquisition_trade_date", nullable = false)
    private LocalDate acquisitionTradeDate;

    @Column(name = "volume", nullable = false)
    private Integer volume = 0;

    @Column(name = "remaining_volume", nullable = false)
    private Integer remainingVolume = 0;

    @Column(name = "cost_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal costPrice;

    @Column(name = "price_source", length = 30)
    private String priceSource;

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
