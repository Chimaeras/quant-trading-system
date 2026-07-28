package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 条件选股器模板：保存用户的筛选条件组合，便于复用。
 */
@Data
@Entity
@Table(
        name = "screener_template",
        indexes = {
                @Index(name = "idx_screener_tpl_user", columnList = "userId"),
                @Index(name = "idx_screener_tpl_user_name", columnList = "userId,name", unique = true)
        }
)
public class ScreenerTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String keyword;

    @Column(length = 20)
    private String exchange;

    @Column(length = 50)
    private String industry;

    @Column(name = "min_listing_days")
    private Integer minListingDays;

    @Column(name = "exclude_st")
    private Boolean excludeSt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
