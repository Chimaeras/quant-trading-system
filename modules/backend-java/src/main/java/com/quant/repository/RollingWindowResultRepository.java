package com.quant.repository;

import com.quant.entity.RollingWindowResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RollingWindowResultRepository extends JpaRepository<RollingWindowResult, Long> {

    List<RollingWindowResult> findByExperimentIdOrderByWindowNoAsc(String experimentId);

    Optional<RollingWindowResult> findByExperimentIdAndWindowNo(String experimentId, Integer windowNo);
}
