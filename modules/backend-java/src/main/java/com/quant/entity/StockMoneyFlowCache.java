package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个股近 5 个交易日主力净流入（亿元）缓存，供市场轮动子分使用。
 */
@Data
@Entity
@Table(name = "stock_money_flow_cache", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "trade_date"})
})
public class StockMoneyFlowCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 近 5 个交易日主力净流入之和（亿元） */
    private Double mainNetInflow5dYi;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
