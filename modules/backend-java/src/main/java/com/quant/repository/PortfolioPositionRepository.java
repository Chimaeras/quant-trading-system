package com.quant.repository;

import com.quant.entity.PortfolioPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortfolioPositionRepository extends JpaRepository<PortfolioPosition, Long> {

    List<PortfolioPosition> findByRunIdOrderByStockCodeAsc(String runId);
}
