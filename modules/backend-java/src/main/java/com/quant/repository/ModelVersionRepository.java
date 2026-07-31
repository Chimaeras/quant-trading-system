package com.quant.repository;

import com.quant.entity.ModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    Optional<ModelVersion> findByModelVersion(String modelVersion);

    List<ModelVersion> findByAlgorithmOrderByCreatedAtDesc(String algorithm);

    List<ModelVersion> findByStageOrderByCreatedAtDesc(String stage);
}
