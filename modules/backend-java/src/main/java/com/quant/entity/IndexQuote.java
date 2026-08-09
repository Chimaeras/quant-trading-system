package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 指数行情数据（QP-005）。
 *
 * <p>接入沪深 300、中证 500、中证 1000、上证指数，供市场环境分析与基准对比使用。
 * 当前 source=eastmoney、reliability=limited：由东方财富指数日 K 拉取；
 * 后续接入 Tushare 指数接口后可替换 reliability=official。
 *
 * <p>同一 (index_code, trade_date) 唯一，避免重复拉取。
 */
@Data
@Entity
@Table(name = "index_quote", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"index_code", "trade_date"})
})
public class IndexQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 指数代码：000300/000905/000852/000001。 */
    @Column(nullable = false, length = 20)
    private String indexCode;

    /** 指数名称：沪深300/中证500/中证1000/上证指数。 */
    @Column(length = 50)
    private String indexName;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** V2-MP-015：价格 scale 4。 */
    @Column(precision = 20, scale = 4)
    private BigDecimal openPrice;
    @Column(precision = 20, scale = 4)
    private BigDecimal closePrice;
    @Column(precision = 20, scale = 4)
    private BigDecimal highPrice;
    @Column(precision = 20, scale = 4)
    private BigDecimal lowPrice;

    /** 成交量（指数口径，万手）。 */
    private Long volume;

    /** 成交额（指数口径，万元）。V2-MP-015：金额 scale 4。 */
    @Column(precision = 24, scale = 4)
    private BigDecimal amount;

    /** 涨跌幅%。V2-MP-015：scale 8。 */
    @Column(precision = 20, scale = 8)
    private BigDecimal changePct;

    /** 来源：eastmoney / tushare。 */
    @Column(nullable = false, length = 40)
    private String source;

    /** 可靠度：limited / official。 */
    @Column(length = 20)
    private String reliability;

    @Column(updatable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    protected void onCreate() {
        ingestedAt = LocalDateTime.now();
    }
}
