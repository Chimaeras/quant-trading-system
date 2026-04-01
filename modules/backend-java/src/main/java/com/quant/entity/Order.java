package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "`order`")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", unique = true, nullable = false, length = 50)
    private String orderId;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String action;  // buy/sell

    @Column(length = 20)
    private String type = "limit";  // limit/market

    @Column(nullable = false)
    private Integer volume;

    @Column(precision = 20, scale = 4)
    private BigDecimal price;

    @Column(precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(length = 20)
    private String status = "pending";  // pending/submitted/filled/cancelled

    private Long strategyId;

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
