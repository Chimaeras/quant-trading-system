package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自选股池：用户关注的标的列表。
 */
@Data
@Entity
@Table(
        name = "watchlist",
        indexes = {
                @Index(name = "idx_watchlist_user", columnList = "userId"),
                @Index(name = "idx_watchlist_user_stock", columnList = "userId,stockCode,exchangeType", unique = true)
        }
)
public class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "exchange_type", nullable = false, length = 20)
    private String exchangeType;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
