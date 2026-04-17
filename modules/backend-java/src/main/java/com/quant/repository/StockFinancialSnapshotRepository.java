package com.quant.repository;

import com.quant.entity.StockFinancialSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StockFinancialSnapshotRepository extends JpaRepository<StockFinancialSnapshot, Long> {

    Optional<StockFinancialSnapshot> findTopByStockCodeAndExchangeTypeOrderByReportPeriodDesc(
            String stockCode, String exchangeType);
}
