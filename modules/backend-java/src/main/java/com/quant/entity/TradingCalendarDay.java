package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 真实交易日历（QP-005）。
 *
 * <p>覆盖法定节假日与临时休市，不再用「自然日排除周末」近似。
 * 当前 source=eastmoney_derived、reliability=limited：由上证指数日 K 的 trade_date
 * 集合反推（指数有行情即为交易日），非官方交易日历表，极端情况（指数缺日）可能漏判；
 * 后续接入 Tushare trade_cal 官方日历后替换 source=tushare、reliability=official。
 *
 * <p>同一 trade_date 唯一，避免重复初始化。
 */
@Data
@Entity
@Table(name = "trading_calendar", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"trade_date"})
})
public class TradingCalendarDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 是否为交易日（true=交易日，false=休市日，当前仅入库交易日行）。 */
    @Column(nullable = false)
    private Boolean isTradingDay = true;

    /** 来源：eastmoney_derived（指数K线反推）/ tushare（官方日历）。 */
    @Column(nullable = false, length = 40)
    private String source;

    /** 可靠度：limited（反推）/ official（官方日历）。 */
    @Column(length = 20)
    private String reliability;

    @Column(updatable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    protected void onCreate() {
        ingestedAt = LocalDateTime.now();
    }
}
