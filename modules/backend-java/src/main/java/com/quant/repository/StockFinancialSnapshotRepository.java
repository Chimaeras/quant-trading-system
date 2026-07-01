package com.quant.repository;

import com.quant.entity.StockFinancialSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockFinancialSnapshotRepository extends JpaRepository<StockFinancialSnapshot, Long> {

    Optional<StockFinancialSnapshot> findTopByStockCodeAndExchangeTypeOrderByReportPeriodDesc(
            String stockCode, String exchangeType);

    /** 按公告日过滤；含 announcementDate 为空的旧数据行（由 Loader 做保守可用日过滤） */
    List<StockFinancialSnapshot> findByAnnouncementDateLessThanEqualOrAnnouncementDateIsNull(LocalDate asOfDate);
}
