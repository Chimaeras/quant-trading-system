package com.quant.repository;

import com.quant.entity.PortfolioNavPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortfolioNavPointRepository extends JpaRepository<PortfolioNavPoint, Long> {

    List<PortfolioNavPoint> findByRunIdOrderByTradeDateAsc(String runId);

    long countByRunId(String runId);
}
