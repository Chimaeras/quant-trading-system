package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * 组合回测订单明细（V2-MP-020）：每 run 按 seq 顺序编号，含 FILLED 与 REJECTED（拒绝漏斗）。
 */
@Data
@Entity
@Table(name = "portfolio_order", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"run_id", "seq"})
})
public class PortfolioOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String runId;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false, length = 8)
    private String action;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 100)
    private String stockName;

    /** FILLED | REJECTED。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 64)
    private String rejectReason;

    private Double price;
    private Integer shares;
    /** BUY=含佣金总成本；SELL=净到账（扣佣金+印花税）。 */
    private Double amount;
    private Double grossAmount;
    private Double commission;
    private Double stampTax;
    /** market_price_raw（成交价来源，MP-005 raw-only）。 */
    @Column(length = 32)
    private String priceSource;

    private LocalDate signalDate;
    private LocalDate exitDate;
    /** OPEN | CLOSE：同一交易日开盘 BUY 先于收盘 SELL（MP-005 现金因果）。 */
    @Column(length = 8)
    private String eventTime;
}
