package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "position")
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(length = 50)
    private String name;

    @Column(columnDefinition = "INT DEFAULT 0")
    private Integer volume = 0;

    @Column(precision = 20, scale = 4)
    private BigDecimal avgPrice;

    @Column(precision = 20, scale = 4)
    private BigDecimal currentPrice;

    @Column(precision = 20, scale = 4)
    private BigDecimal marketValue;

    @Column(precision = 20, scale = 4)
    private BigDecimal pnl;

    @Column(precision = 10, scale = 4)
    private BigDecimal pnlRatio;

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
