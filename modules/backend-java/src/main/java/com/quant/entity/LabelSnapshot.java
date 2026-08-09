package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 标签快照（QP-008）。
 *
 * <p>基于 QP-007 历史重放股票池，按 {@link com.quant.research.ResearchConvention} 口径
 * 生成 T+2/T+3 标签：二分类 y_h、净收益 net_return、入场/退出价、成交状态。
 *
 * <p>标签生成时保留所有拒单/无法退出样本（NOT_BUYABLE/SUSPENDED/NOT_SELLABLE/DELISTED），
 * 不用删除来「净化」结果；分别统计各类 reject 率。
 *
 * <p>口径（ResearchConvention）：
 * <ul>
 *   <li>入场：signal_date 下一交易日开盘价 ×(1+佣金+滑点)。</li>
 *   <li>退出：入场后第 h 个持有交易日收盘价 ×(1-印花税-滑点)。</li>
 *   <li>net_return = exit_proceeds / entry_cost - 1。</li>
 *   <li>y_h = 1[net_return > min_net_return]，h ∈ {2,3} 分别独立建模。</li>
 * </ul>
 *
 * <p>同一 (label_version, signal_date, stock_code, exchange_type, horizon) 唯一，幂等跳过。
 */
@Data
@Entity
@Table(name = "label_snapshot", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"label_version", "signal_date", "stock_code", "exchange_type", "horizon"})
})
public class LabelSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 标签版本：如 label_v1_t2 / label_v1_t3；不同阈值/成本须另立版本。 */
    @Column(nullable = false, length = 40)
    private String labelVersion;

    @Column(nullable = false)
    private LocalDate signalDate;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    /** 持有期（交易日）：1（T+1 方向）或 2/3（T+2/T+3 成交口径）。 */
    @Column(nullable = false)
    private Integer horizon;

    /**
     * 标签类型：TRADED（成交口径：开入场→持有期收盘退出，扣成本，用于 T+2/T+3）；
     * T1_DIRECTION（T+1 次日方向：close[t+1]/close[t]−1，无成本，仅预测次日收盘方向）。
     */
    @Column(nullable = false, length = 20)
    private String labelType = "TRADED";

    /** 二分类标签：1=上涨(net_return>阈值)，0=未上涨；拒单样本为 null。 */
    private Integer yLabel;

    /** 净收益（扣成本后）；拒单样本为 null。V2-MP-015：scale 8，防 0.0003 截成 0.00。 */
    @Column(precision = 20, scale = 8)
    private BigDecimal netReturn;

    /** 入场价（含滑点）。V2-MP-015：价格 scale 4。 */
    @Column(precision = 20, scale = 4)
    private BigDecimal entryPrice;

    /** 退出价（含滑点）。V2-MP-015：价格 scale 4。 */
    @Column(precision = 20, scale = 4)
    private BigDecimal exitPrice;

    private LocalDate entryDate;
    private LocalDate exitDate;

    /** 持有期最大有利变动（正收益峰值），FILLED 时计算，否则 null。V2-MP-015：scale 8。 */
    @Column(precision = 20, scale = 8)
    private BigDecimal maxFavorable;

    /** 持有期最大不利变动（负收益谷值），FILLED 时计算，否则 null。V2-MP-015：scale 8。 */
    @Column(precision = 20, scale = 8)
    private BigDecimal maxAdverse;

    /** 成交状态：FILLED/NOT_BUYABLE/SUSPENDED/NOT_SELLABLE/DELISTED。 */
    @Column(nullable = false, length = 20)
    private String execStatus;

    /** 来源：market_price_raw（label_v2）/ derived_local（label_v1 legacy）。 */
    @Column(nullable = false, length = 40)
    private String source;

    /** 可选：构建时引用的数据快照 ID。 */
    @Column(length = 40)
    private String dataSnapshotId;

    /** 成交价来源：market_price_raw / market_data。 */
    @Column(length = 40)
    private String priceSource;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
