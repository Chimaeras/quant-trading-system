package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史证券主数据（QP-004）。
 *
 * <p>记录证券名称、ST/退市状态、行业、上市/退市日期的「有效期」历史，
 * 消除历史时点 ST 变更/行业变更/退市的前视风险（point-in-time）。
 *
 * <p>查询 {@code as_of=2023-06-30} 时，应返回 {@code valid_from <= as_of < valid_to} 的记录，
 * 不出现该日之后才发生的改名/ST/退市信息。
 *
 * <p>当前 source=current_snapshot、reliability=limited：用 stock_info 当前快照初始化一条
 * valid_from=上市日、valid_to=null（无限期）的记录，无历史状态变更数据；
 * 后续接入东方财富/Tushare 官方历史后替换 source 并拆分多条有效期记录。
 *
 * <p>同一 (stock_code, exchange_type, valid_from) 唯一，避免重复初始化。
 */
@Data
@Entity
@Table(name = "security_master_history", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "valid_from"})
})
public class SecurityMasterHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    /** 证券名称（含 ST/*ST 前缀，用于历史时点 ST 判定）。 */
    @Column(length = 50)
    private String stockName;

    /** 行业（历史时点行业，用于行业中性化）。 */
    @Column(length = 50)
    private String industry;

    /** 上市日期。 */
    private LocalDate listDate;

    /** 退市日期；未退市为 null。 */
    private LocalDate delistDate;

    /** 本条记录生效起始日（含）。 */
    @Column(nullable = false)
    private LocalDate validFrom;

    /** 本条记录生效截止日（不含）；null 表示无限期（至今有效）。 */
    private LocalDate validTo;

    /** 来源：current_snapshot（当前快照初始化）/ eastmoney / tushare。 */
    @Column(nullable = false, length = 40)
    private String source;

    /** 可靠度：limited（快照，无历史变更）/ official。 */
    @Column(length = 20)
    private String reliability;

    @Column(updatable = false)
    private LocalDateTime ingestedAt;

    @PrePersist
    protected void onCreate() {
        ingestedAt = LocalDateTime.now();
    }
}
