package com.quant.repository;

import com.quant.entity.ModelVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    Optional<ModelVersion> findByModelVersion(String modelVersion);

    List<ModelVersion> findByAlgorithmOrderByCreatedAtDesc(String algorithm);

    List<ModelVersion> findByStageOrderByCreatedAtDesc(String stage);

    /** 最近注册的 N 条模型版本（供模型注册表页面展示）。 */
    List<ModelVersion> findTop20ByOrderByCreatedAtDesc();

    /** 指定持有期 + 阶段的最近模型（用于候选推理加载，如 horizon=1 + champion）。 */
    java.util.Optional<ModelVersion> findFirstByHorizonAndStageOrderByCreatedAtDesc(Integer horizon, String stage);

    /** 指定持有期的最新模型（不限阶段，用于回退加载）。 */
    java.util.Optional<ModelVersion> findFirstByHorizonOrderByCreatedAtDesc(Integer horizon);

    /** 按精确 deploy key（algorithm:feature_set:schema_hash）取最近版本（V2-MP-025 推理契约）。 */
    java.util.Optional<ModelVersion> findFirstByDeployKeyOrderByCreatedAtDesc(String deployKey);

    /** 按 deploy key + stage + 策略 + 持有期取最近版本（V2-MP-004 原子部署契约查找）。 */
    java.util.Optional<ModelVersion> findFirstByDeployKeyAndStageAndStrategyTypeAndHorizonOrderByCreatedAtDesc(
            String deployKey, String stage, String strategyType, Integer horizon);

    /** 幂等导入：同 model_version 且 artifact_hash 一致视为同一产物，重复导入返回既有记录。 */
    java.util.Optional<ModelVersion> findFirstByModelVersionAndArtifactHash(String modelVersion, String artifactHash);

    /**
     * 按 deploy key 悲观锁定该部署槽位全部版本行（V2-MP-018 晋级事务）。
     * PESSIMISTIC_WRITE（SELECT ... FOR UPDATE）串行化并发晋级：同 deploy key 恰好最多一个 champion。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ModelVersion m where m.deployKey = :deployKey")
    List<ModelVersion> lockByDeployKey(String deployKey);
}
