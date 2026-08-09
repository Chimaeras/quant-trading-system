package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * 组合回测期末持仓（V2-MP-020）：未平仓仓位显式终态（OPEN_NOT_YET_EXPIRED / OPEN_UNRESOLVED）。
 */
@Data
@Entity
@Table(name = "portfolio_position", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"run_id", "stock_code", "exchange_type"})
})
public class PortfolioPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String runId;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 100)
    private String stockName;

    private Integer shares;
    private Double entryPrice;
    private Double markPrice;
    private LocalDate entryDate;
    private LocalDate exitDate;

    @Column(length = 32)
    private String finalStatus;

    private Double unrealizedPnl;
    /** NAV 标记价来源（market_price_raw 优先，qfq_legacy 仅净值展示）。 */
    @Column(length = 32)
    private String priceSource;
}
