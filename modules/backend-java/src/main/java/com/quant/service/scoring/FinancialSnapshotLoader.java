package com.quant.service.scoring;

import com.quant.entity.StockFinancialSnapshot;
import com.quant.repository.StockFinancialSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 批量预加载最新财报快照，避免 N+1 查询。
 */
@Component
@RequiredArgsConstructor
public class FinancialSnapshotLoader {

    private final StockFinancialSnapshotRepository snapshotRepository;

    public Map<String, StockFinancialSnapshot> loadLatestByStockKey() {
        Map<String, StockFinancialSnapshot> latest = new HashMap<>();
        for (StockFinancialSnapshot s : snapshotRepository.findAll()) {
            if (s.getStockCode() == null || s.getExchangeType() == null || s.getReportPeriod() == null) {
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

    public static Double toDouble(java.math.BigDecimal v) {
        return v != null ? v.doubleValue() : null;
    }
}
