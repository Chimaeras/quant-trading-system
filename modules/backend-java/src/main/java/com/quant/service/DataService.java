package com.quant.service;

import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据中心服务 - 数据概览等统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataService {

    private final MarketDataRepository marketDataRepo;
    private final StockInfoRepository stockInfoRepo;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 获取数据概览：证券数量、最早/最晚日期、数据完整性、存储占用
     */
    public Map<String, Object> getOverview() {
        Map<String, Object> result = new HashMap<>();

        // 1. 证券数量：优先用 market_data 中的去重数量（有行情数据的证券），否则用 stock_info
        long stockCount;
        long dataCount = marketDataRepo.count();
        if (dataCount > 0) {
            stockCount = marketDataRepo.countDistinctStocks();
        } else {
            stockCount = stockInfoRepo.count();
        }
        result.put("stock_count", (int) stockCount);

        // 2. 最早日期、最后日期
        Optional<LocalDate> minDate = marketDataRepo.findMinTradeDate();
        Optional<LocalDate> maxDate = marketDataRepo.findMaxTradeDate();
        result.put("earliest_date", minDate.map(LocalDate::toString).orElse("-"));
        result.put("latest_date", maxDate.map(LocalDate::toString).orElse("-"));

        // 3. 数据完整性：实际记录数 / 期望记录数（证券数 × 交易日数）
        double completeness = 0;
        if (stockCount > 0 && minDate.isPresent() && maxDate.isPresent() && dataCount > 0) {
            long days = ChronoUnit.DAYS.between(minDate.get(), maxDate.get()) + 1;
            long tradingDays = estimateTradingDays(days);
            long expected = stockCount * Math.max(1, tradingDays);
            completeness = expected > 0 ? Math.min(100, (double) dataCount / expected * 100) : 0;
        }
        result.put("completeness", Math.round(completeness * 10) / 10.0);

        // 4. 存储占用：查询 MySQL 表大小
        result.put("storage_size", formatStorageSize(estimateStorageSize()));

        long listDateMissing = stockInfoRepo.countByListDateIsNull();
        result.put("list_date_missing_count", listDateMissing);
        long listDateBackfillable = listDateMissing > 0
                ? marketDataRepo.countBackfillableListDateCandidates()
                : 0L;
        result.put("list_date_backfillable_count", listDateBackfillable);

        return result;
    }

    /** 估算交易日数量（去除周末，简化按 5/7） */
    private long estimateTradingDays(long calendarDays) {
        return Math.max(1, calendarDays * 5 / 7);
    }

    private long estimateStorageSize() {
        try {
            Query q = entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name IN ('market_data', 'stock_info')");
            Object val = q.getSingleResult();
            return val instanceof Number ? ((Number) val).longValue() : 0;
        } catch (Exception e) {
            log.debug("无法获取表大小: {}", e.getMessage());
            return 0;
        }
    }

    private String formatStorageSize(long bytes) {
        if (bytes <= 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 用 market_data 最早交易日回填 stock_info.list_date（仅补空值，不覆盖已有上市日）。
     * 口径：K 线最早交易日为上市日代理，受本地行情窗口限制，非交易所官方上市日。
     */
    @Transactional
    public Map<String, Object> backfillListDateFromMarketData() {
        long missingBefore = stockInfoRepo.countByListDateIsNull();
        List<Object[]> candidates = marketDataRepo.findEarliestTradeDatesForMissingListDate();
        int backfillable = candidates.size();
        int updated = 0;
        int skippedNoMatch = 0;

        for (Object[] row : candidates) {
            if (row == null || row.length < 3 || row[0] == null || row[1] == null || row[2] == null) {
                continue;
            }
            String stockCode = String.valueOf(row[0]);
            String exchangeType = String.valueOf(row[1]);
            LocalDate earliest = toLocalDate(row[2]);
            if (earliest == null) {
                continue;
            }
            Optional<StockInfo> infoOpt = stockInfoRepo.findByStockCodeAndExchangeType(stockCode, exchangeType);
            if (infoOpt.isEmpty() || infoOpt.get().getListDate() != null) {
                skippedNoMatch++;
                continue;
            }
            StockInfo info = infoOpt.get();
            info.setListDate(earliest);
            stockInfoRepo.save(info);
            updated++;
        }

        long missingAfter = stockInfoRepo.countByListDateIsNull();
        Map<String, Object> result = new HashMap<>();
        result.put("missing_before", missingBefore);
        result.put("backfillable", backfillable);
        result.put("updated", updated);
        result.put("skipped_no_match", skippedNoMatch);
        result.put("missing_after", missingAfter);
        result.put("no_market_data", Math.max(0, missingAfter));
        result.put("source", "market_data_earliest_trade_date");
        result.put("note", "K线最早交易日为上市日代理，受本地行情窗口限制；非交易所官方上市日");
        log.info("list_date 回填完成: missing_before={}, updated={}, missing_after={}",
                missingBefore, updated, missingAfter);
        return result;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.util.Date utilDate) {
            return new Date(utilDate.getTime()).toLocalDate();
        }
        return null;
    }
}
