package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 国内财经政策/新闻按行业聚合的日度得分（与 GDELT 地缘因子独立）。
 */
@Data
@Entity
@Table(name = "policy_industry_daily", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"trade_date", "industry"})
})
public class PolicyIndustryDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 80)
    private String industry;

    /** 约 -10～+10 */
    private Double score;

    @Column(length = 500)
    private String note;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
