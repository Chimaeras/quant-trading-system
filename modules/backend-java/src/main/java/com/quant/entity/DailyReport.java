package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 选股日报（V2-MP-012）：prediction snapshot 的不可变投影头。
 *
 * <p>日报正文候选 = {@link PredictionSnapshotItem}（不可变，唯一约束 trade_date/horizon/
 * track/publish_version/stock_code/exchange_type），本表只保存投影头 + content_hash +
 * 到期后追加的 realization（追加不修改原候选）。
 *
 * <p>不可变语义：每个 {@code (trade_date, horizon, track, publish_version)} 恰好一条；
 * 重跑 selector/prediction 只会产生更高 publish_version 的新投影（新 report_version），
 * 绝不覆盖历史报告。content_hash 为候选集合的确定性 SHA-256，读回时复算比对，不一致即 fail-closed。
 */
@Data
@Entity
@Table(name = "daily_report", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"report_id"}),
        @UniqueConstraint(columnNames = {"trade_date", "horizon", "track", "publish_version"})
})
public class DailyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 确定性 reportId：RPT + sha256(tradeDate|horizon|track|publishVersion) 前 16 hex。 */
    @Column(nullable = false, length = 40)
    private String reportId;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Integer horizon;

    @Column(nullable = false, length = 32)
    private String track;

    /** 所投影 prediction snapshot 的发布版本（不可变）。 */
    @Column(nullable = false)
    private Integer publishVersion;

    @Column(nullable = false, length = 40)
    private String predictionRunId;

    @Column(length = 32)
    private String sourceResultId;

    @Column(length = 40)
    private String factorVersion;

    @Column(length = 60)
    private String modelVersion;

    @Column(length = 40)
    private String outputKind;

    @Column(length = 64)
    private String schemaHash;

    @Column(length = 120)
    private String deployKey;

    @Column(length = 60)
    private String calibratorVersion;

    @Column(length = 64)
    private String calibratorHash;

    /** 数据快照 ID（来自 prediction lineage 的 checked_snapshot_id）。 */
    @Column(length = 40)
    private String snapshotId;

    /** 候选集合确定性 SHA-256（正文一致性校验键）。 */
    @Column(nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private Integer itemCount = 0;

    /** 报告版本 = 所投影 prediction snapshot 的 publish_version（内容只增不改）。 */
    @Column(nullable = false)
    private Integer reportVersion = 1;

    /** 到期后追加的 realization JSON（幂等落一次，不覆盖）。 */
    @Column(columnDefinition = "LONGTEXT")
    private String realizationJson;

    @Column(updatable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        generatedAt = LocalDateTime.now();
    }
}
