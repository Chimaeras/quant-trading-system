package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 50)
    private String broker;

    @Column(name = "account_no", length = 50)
    private String accountNo;

    @Column(precision = 20, scale = 4)
    private BigDecimal totalAsset = BigDecimal.ZERO;

    @Column(precision = 20, scale = 4)
    private BigDecimal cash = BigDecimal.ZERO;

    @Column(precision = 20, scale = 4)
    private BigDecimal frozen = BigDecimal.ZERO;

    @Column(precision = 20, scale = 4)
    private BigDecimal positionValue = BigDecimal.ZERO;

    @Column(precision = 20, scale = 4)
    private BigDecimal todayPnl = BigDecimal.ZERO;

    @Column(precision = 20, scale = 4)
    private BigDecimal totalPnl = BigDecimal.ZERO;

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
