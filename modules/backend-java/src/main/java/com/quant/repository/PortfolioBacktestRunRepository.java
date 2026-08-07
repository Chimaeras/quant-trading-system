package com.quant.repository;

import com.quant.entity.PortfolioBacktestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PortfolioBacktestRunRepository extends JpaRepository<PortfolioBacktestRun, Long> {

    Optional<PortfolioBacktestRun> findByRunId(String runId);
}
