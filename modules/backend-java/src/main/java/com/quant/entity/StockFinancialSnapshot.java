package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 最新一期财报衍生指标（用于多维度基本面与行业对比）。
 * 数据可由 akshare 同步脚本或 POST /api/data/financial/bulk 导入。
 */
@Data
@Entity
@Table(name = "stock_financial_snapshot", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "report_period"})
})
public class StockFinancialSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    /** 报告期（如季报末日） */
    @Column(nullable = false)
    private LocalDate reportPeriod;

    /** 营收同比增速 % */
    @Column(precision = 12, scale = 4)
    private BigDecimal revenueYoY;

    /** 净利润同比增速 % */
    @Column(precision = 12, scale = 4)
    private BigDecimal netProfitYoY;

    @Column(precision = 12, scale = 4)
    private BigDecimal roe;

    /** 资产负债率 % */
    @Column(precision = 12, scale = 4)
    private BigDecimal debtRatio;

    /** 市净率 */
    @Column(precision = 12, scale = 4)
    private BigDecimal pbRatio;

    /** 流动比率 */
    @Column(precision = 12, scale = 4)
    private BigDecimal currentRatio;

    @Column(updatable = false)
    private LocalDateTime createdAt;

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
