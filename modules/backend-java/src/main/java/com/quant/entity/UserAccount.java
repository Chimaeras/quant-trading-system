package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "account_name", length = 50)
    private String accountName;

    @Column(name = "account_type", columnDefinition = "TINYINT DEFAULT 1")
    private Integer accountType = 1;

    @Column(name = "total_asset", precision = 20, scale = 4)
    private BigDecimal totalAsset = BigDecimal.ZERO;

    @Column(name = "available_cash", precision = 20, scale = 4)
    private BigDecimal availableCash = BigDecimal.ZERO;

    @Column(name = "frozen_cash", precision = 20, scale = 4)
    private BigDecimal frozenCash = BigDecimal.ZERO;

    @Column(name = "position_value", precision = 20, scale = 4)
    private BigDecimal positionValue = BigDecimal.ZERO;

    @Column(name = "total_profit", precision = 20, scale = 4)
    private BigDecimal totalProfit = BigDecimal.ZERO;

    @Column(name = "today_profit", precision = 20, scale = 4)
    private BigDecimal todayProfit = BigDecimal.ZERO;

    @Column(columnDefinition = "TINYINT DEFAULT 1")
    private Integer status = 1;

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
