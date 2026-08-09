package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 模拟账户历史演练会话（V2-MP-021）。
 *
 * <p>历史订单携带 tradeDate 时，必须挂在某个 HISTORICAL 会话下执行，资金/持仓/订单
 * 全部按 sessionId 隔离，绝不混入在线账户（session_id=0 哨兵）。在线账户不建行。
 */
@Data
@Entity
@Table(name = "sim_session")
public class SimulationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "session_name", length = 80)
    private String sessionName;

    @Column(name = "mode", nullable = false, length = 20)
    private String mode = "HISTORICAL";

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "base_cash", precision = 20, scale = 4)
    private BigDecimal baseCash = BigDecimal.ZERO;

    @Column(name = "available_cash", precision = 20, scale = 4)
    private BigDecimal availableCash = BigDecimal.ZERO;

    @Column(name = "position_value", precision = 20, scale = 4)
    private BigDecimal positionValue = BigDecimal.ZERO;

    @Column(name = "total_profit", precision = 20, scale = 4)
    private BigDecimal totalProfit = BigDecimal.ZERO;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
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
