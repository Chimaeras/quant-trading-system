package com.quant.repository;

import com.quant.entity.BacktestRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BacktestRunRepository extends JpaRepository<BacktestRun, Long> {

    Optional<BacktestRun> findByResultId(String resultId);

    List<BacktestRun> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStrategyType(String strategyType);
}
