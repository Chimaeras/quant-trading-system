package com.quant.repository;

import com.quant.entity.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {

    Optional<DailyReport> findByReportId(String reportId);

    /** 每个 (trade_date, horizon, track, publish_version) 恰好一条（不可变投影头）。 */
    Optional<DailyReport> findByTradeDateAndHorizonAndTrackAndPublishVersion(
            LocalDate tradeDate, Integer horizon, String track, Integer publishVersion);

    List<DailyReport> findByTradeDateOrderByHorizonAscTrackAscPublishVersionDesc(LocalDate tradeDate);
}
