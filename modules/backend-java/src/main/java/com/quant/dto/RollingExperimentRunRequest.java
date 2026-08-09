package com.quant.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 滚动 purged Walk-Forward 实验运行/恢复请求（V2-MP-023）。
 *
 * <p>window_mode：EXPANDING（训练窗从 trainDays 起随 stepDays 增长）或 ROLLING（固定 trainDays，
 * 每次滑 stepDays）。stepDays 不足 testDays 时会被钳制为 testDays，保证 OOS 测试窗严格不重叠。
 * 传 experimentId 时复用该实验（跳过已落库窗口，实现中断恢复）。
 */
@Data
public class RollingExperimentRunRequest {

    /** 绑定策略（必填）：CLASSIC_SELECTOR / OVERSOLD_REBOUND。 */
    private String strategyType;

    /** 数据快照 ID（可选，写入产物 lineage；MP-007 冻结快照就绪前可为空）。 */
    private String snapshotId;

    /** 标签版本（必填，如 label_v2）。 */
    private String labelVersion;

    /** 因子版本（可选，进一步锁定）。 */
    private String featureVersion;

    /** 持有期 T+N（必填，≥1）。 */
    private Integer horizon;

    private LocalDate startDate;
    private LocalDate endDate;

    /** EXPANDING | ROLLING，默认 ROLLING。 */
    private String mode;

    /** 训练窗交易日数（必填，≥1）。 */
    private Integer trainDays;

    /** 校准 fit 窗交易日数（≥0，默认 0 = 直接用 valid=testStart 前一自然日前的空窗，实际请给 ≥1）。 */
    private Integer validDays;

    /** 冻结 OOS 测试窗交易日数（必填，≥1）。 */
    private Integer testDays;

    /** 额外 embargo 交易日（默认 1，叠加 horizon 构成 purge/embargo 间隔）。 */
    private Integer embargoDays;

    /** 窗口步进（交易日，默认 testDays；小于 testDays 会被钳制以保持 OOS 窗不重叠）。 */
    private Integer stepDays;

    /** 可选：复用已存在实验实现中断恢复（幂等跳过已落库窗口）。 */
    private String experimentId;
}
