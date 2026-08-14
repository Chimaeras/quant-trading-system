package com.quant.repository;

import com.quant.entity.ExperimentRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ExperimentRunRepository extends JpaRepository<ExperimentRun, Long> {

    Optional<ExperimentRun> findByExperimentId(String experimentId);

    List<ExperimentRun> findByExperimentIdStartingWith(String prefix);

    /** 统计某日前缀的实验数（用于生成序号，避免水合全部实体）。 */
    long countByExperimentIdStartingWith(String prefix);

    @Query("SELECT e FROM ExperimentRun e ORDER BY e.createdAt DESC")
    List<ExperimentRun> findLatest(Pageable pageable);

    @Query("SELECT COUNT(e) FROM ExperimentRun e")
    long count();
}
