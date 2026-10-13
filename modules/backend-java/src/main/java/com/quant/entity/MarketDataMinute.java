package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 分钟级行情（盯盘 5 分钟 bar 的本地沉淀）。
 *
 * <p>存在意义：日线 {@code market_data} 无法支撑盘中策略——MA_CROSS 等规则需要
 * 5 分钟粒度。此前该粒度只活在 Python 侧的 sqlite「重启恢复」缓存里，那张表既不
 * 清理也无日期边界，导致跨月陈旧 bar 混入策略预热序列、在均线断层处伪造金叉
 * （D-STALE-BAR-01）。落到主库后，预热可优先读本地连续分钟线，外部行情退化为兜底。
 *
 * <p>幂等：{@code (stock_code, period_min, start_ts)} 唯一，写入走
 * {@code ON DUPLICATE KEY UPDATE}——同一根 bar 会被反复提交，必须 upsert。
 */
@Data
@Entity
@Table(name = "market_data_minute", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mdm_code_period_start",
                columnNames = {"stock_code", "period_min", "start_ts"})
})
public class MarketDataMinute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    /** bar 周期（分钟）。 */
    @Column(name = "period_min", nullable = false)
    private Integer periodMin;

    /** bar 起始时刻（北京时区本地时间）。 */
    @Column(name = "start_ts", nullable = false)
    private LocalDateTime startTs;

    @Column(name = "end_ts", nullable = false)
    private LocalDateTime endTs;

    /** 所属交易日，与日线表口径一致，便于按日回测与清理。 */
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "open_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal closePrice;

    /** 成交量，单位为股（Python 侧已把东方财富的「手」换算为股）。 */
    @Column(name = "volume", nullable = false)
    private Long volume;

    /** 来源标记（如 eastmoney_batch / eastmoney_kline），用于区分盯盘实时桶与预热历史。 */
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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
