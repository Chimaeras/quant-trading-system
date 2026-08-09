package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 概率校准器注册表（V2-MP-004）。
 *
 * <p>持久化 PAV/sigmoid 校准器（knots/bins + norm + hash），原子绑定
 * strategy/horizon/factor/label/snapshot/base model/code 版本。
 * stage=champion 的校准器 + 完整 lineage 的 champion base model 才产生概率 items。
 */
@Data
@Entity
@Table(name = "calibrator_registry", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"calibrator_version"})
})
public class Calibrator {

    public static final String STAGE_CHAMPION = "champion";
    public static final String STAGE_CHALLENGER = "challenger";
    public static final String STAGE_ARCHIVED = "archived";
    public static final String STAGE_DRAFT = "DRAFT";

    public static final String KIND_PAV_ISOTONIC = "pav_isotonic";
    public static final String KIND_PLATT_SIGMOID = "platt_sigmoid";
    public static final String INPUT_RULE_SCORE = "rule_score";
    public static final String INPUT_BASE_PROBABILITY = "base_probability";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 确定性版本：calib_<strategy>_h<horizon>_<hash16>。 */
    @Column(nullable = false, length = 60)
    private String calibratorVersion;

    @Column(length = 40)
    private String strategyType;

    private Integer horizon;

    @Column(length = 64)
    private String factorVersion;

    @Column(length = 40)
    private String labelVersion;

    @Column(length = 40)
    private String dataSnapshotId;

    /** pav_isotonic | platt_sigmoid。 */
    @Column(length = 32)
    private String outputKind;

    /** rule_score | base_probability。 */
    @Column(length = 32)
    private String inputDomain;

    /** 被校准的 base model（原子绑定，晋级/发布要求一致）。 */
    @Column(length = 60)
    private String baseModelVersion;

    @Column(length = 32)
    private String baseOutputKind;

    /** 自包含 JSON：kind/input_domain/norm/sigmoid/breakpoints/fit+eval 窗。 */
    @Column(columnDefinition = "TEXT")
    private String calibratorJson;

    @Column(length = 64)
    private String calibratorHash;

    @Column(length = 64)
    private String codeVersion;

    private LocalDate fitStart;
    private LocalDate fitEnd;
    private LocalDate evalStart;
    private LocalDate evalEnd;

    /** champion / challenger / archived / DRAFT。 */
    @Column(nullable = false, length = 20)
    private String stage = STAGE_CHALLENGER;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
