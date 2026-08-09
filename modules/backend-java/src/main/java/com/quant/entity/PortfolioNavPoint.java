package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * 组合回测 NAV 曲线（V2-MP-020）：逐交易日一行，异步执行时批量落库。
 */
@Data
@Entity
@Table(name = "portfolio_nav_point", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"run_id", "trade_date"})
})
public class PortfolioNavPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String runId;

    @Column(nullable = false)
    private LocalDate tradeDate;

    private Double cash;
    private Double positionValue;
    private Double nav;
    private Integer positions;
}
