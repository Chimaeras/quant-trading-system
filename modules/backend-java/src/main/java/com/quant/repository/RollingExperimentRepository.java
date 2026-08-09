package com.quant.repository;

import com.quant.entity.RollingExperiment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RollingExperimentRepository extends JpaRepository<RollingExperiment, Long> {

    Optional<RollingExperiment> findByExperimentId(String experimentId);

    List<RollingExperiment> findTop20ByOrderByUpdatedAtDesc();
}
