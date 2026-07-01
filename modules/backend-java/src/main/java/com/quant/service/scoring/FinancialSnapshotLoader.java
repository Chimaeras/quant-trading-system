package com.quant.service.scoring;

import com.quant.entity.StockFinancialSnapshot;
import com.quant.repository.StockFinancialSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

/**
 * 批量预加载最新财报快照，避免 N+1 查询。
 */
@Component
@RequiredArgsConstructor
public class FinancialSnapshotLoader {

    private final StockFinancialSnapshotRepository snapshotRepository;

    public Map<String, StockFinancialSnapshot> loadLatestByStockKey(LocalDate asOfDate) {
        Map<String, StockFinancialSnapshot> latest = new HashMap<>();
        List<StockFinancialSnapshot> candidates = asOfDate == null
                ? snapshotRepository.findAll()
                : snapshotRepository.findByAnnouncementDateLessThanEqualOrAnnouncementDateIsNull(asOfDate);
        for (StockFinancialSnapshot s : candidates) {
            if (s.getStockCode() == null || s.getExchangeType() == null || s.getReportPeriod() == null) {
                continue;
            }
            LocalDate availableDate = s.getAnnouncementDate() != null
                    ? s.getAnnouncementDate()
                    : conservativeAvailabilityDate(s.getReportPeriod());
            if (asOfDate != null && availableDate.isAfter(asOfDate)) {
                continue;
            }
            String key = s.getStockCode() + "|" + s.getExchangeType();
            StockFinancialSnapshot existing = latest.get(key);
            if (existing == null || s.getReportPeriod().isAfter(existing.getReportPeriod())) {
                latest.put(key, s);
            }
        }
        return latest;
    }

    /**
     * 旧数据没有公告日期时采用保守可用日，避免直接把报告期末视为已知。
     * 新导入数据应始终提供 announcementDate。
     */
    static LocalDate conservativeAvailabilityDate(LocalDate reportPeriod) {
        return reportPeriod.plusMonths(4);
    }

    public static Double toDouble(java.math.BigDecimal v) {
        return v != null ? v.doubleValue() : null;
    }
}
