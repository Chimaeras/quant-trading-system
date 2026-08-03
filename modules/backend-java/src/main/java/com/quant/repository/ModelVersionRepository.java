package com.quant.repository;

import com.quant.entity.ModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
