package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 多维度综合打分（技术面文档版 + 基本面 + 政策 + 轮动 + 行业对比）。
 */
@Data
@Entity
@Table(name = "stock_multi_dim_score", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "trade_date", "factor_version"})
})
public class StockMultiDimScore {

    /** 第一版：仅使用库内 K 线 + 日线 PE + 行业/板块聚合可计算因子（不依赖外部行情/API） */
    public static final String FACTOR_VERSION = "multi_dim_v2_normalized";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 100)
    private String stockName;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 32)
    private String factorVersion;

    /** 文档：MACD/RSI/KDJ/成交量加权 */
    private Double scoreTechnical;

    private Double scoreFundamental;
    private Double scorePolicy;
    private Double scoreRotation;
    private Double scoreIndustryCompare;
    private Double scoreSentiment;
    private Double scoreValuation;
    private Double scoreProfitability;
    private Double scoreFinancialHealth;

    private Double compositeScore;

    @Column(length = 16)
    private String suggestion;

    /** 技术面子项（文档口径） */
    private Double techMacd;
    private Double techRsi;
    private Double techKdj;
    private Double techVolume;
    private Double techMa;

    @Column(length = 32)
    private String macdStatus;
    @Column(length = 32)
    private String rsiStatus;
    @Column(length = 32)
    private String kdjStatus;
    @Column(length = 32)
    private String volumeStatus;
    @Column(length = 32)
    private String maStatus;

    @Column(length = 1000)
    private String reasonsJson;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
