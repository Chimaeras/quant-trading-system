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
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * 最近 N 个交易日热门度榜单。
     * 口径：成交额规模 35% + 换手率 25% + 相对前 20 个交易日量能放大 25% + 上涨天数占比 15%。
     */
    public Map<String, Object> getHotStocks(int rawLimit, int rawDays, int rawBaselineDays, LocalDate endDate) {
        int limit = clamp(rawLimit, 1, 500);
        int days = clamp(rawDays, 3, 30);
        int baselineDays = clamp(rawBaselineDays, 5, 120);

        String endDateFilter = endDate != null ? "WHERE trade_date <= :endDate" : "";
        String sql = """
                WITH recent_dates AS (
                    SELECT trade_date
                    FROM (
                        SELECT DISTINCT trade_date
                        FROM market_data
                        %s
                        ORDER BY trade_date DESC
                        LIMIT %d
                    ) d
                ),
                bounds AS (
                    SELECT MIN(trade_date) AS start_date, MAX(trade_date) AS end_date, COUNT(*) AS trade_days
                    FROM recent_dates
                ),
                baseline_dates AS (
                    SELECT trade_date
                    FROM (
                        SELECT DISTINCT m.trade_date
                        FROM market_data m
                        CROSS JOIN bounds b
                        WHERE m.trade_date < b.start_date
                        ORDER BY m.trade_date DESC
                        LIMIT %d
                    ) d
                ),
                recent AS (
                    SELECT
                        m.stock_code,
                        m.exchange_type,
                        COUNT(*) AS trade_days,
                        AVG(COALESCE(m.volume, 0)) AS avg_volume,
                        AVG(COALESCE(m.amount, 0)) AS avg_amount,
                        AVG(COALESCE(m.turnover_rate, 0)) AS avg_turnover,
                        SUM(CASE WHEN COALESCE(m.change_pct, 0) > 0 THEN 1 ELSE 0 END) AS up_days
                    FROM market_data m
                    INNER JOIN recent_dates d ON d.trade_date = m.trade_date
                    GROUP BY m.stock_code, m.exchange_type
                ),
                latest AS (
                    SELECT stock_code, exchange_type, close_price, change_pct
                    FROM (
                        SELECT
                            m.stock_code,
                            m.exchange_type,
                            m.close_price,
                            m.change_pct,
                            ROW_NUMBER() OVER (
                                PARTITION BY m.stock_code, m.exchange_type
                                ORDER BY m.trade_date DESC
                            ) AS rn
                        FROM market_data m
                        INNER JOIN recent_dates d ON d.trade_date = m.trade_date
                    ) x
                    WHERE rn = 1
                ),
                baseline AS (
                    SELECT
                        m.stock_code,
                        m.exchange_type,
                        AVG(COALESCE(m.volume, 0)) AS base_avg_volume,
                        AVG(COALESCE(m.amount, 0)) AS base_avg_amount
                    FROM market_data m
                    INNER JOIN baseline_dates d ON d.trade_date = m.trade_date
                    GROUP BY m.stock_code, m.exchange_type
                )
                SELECT
                    r.stock_code,
                    COALESCE(s.stock_name, '') AS stock_name,
                    r.exchange_type,
                    COALESCE(s.industry, '') AS industry,
                    r.trade_days,
                    ROUND(r.avg_volume, 0) AS avg_volume,
                    ROUND(r.avg_amount, 2) AS avg_amount,
                    ROUND(r.avg_turnover, 2) AS avg_turnover,
                    ROUND(COALESCE(r.avg_volume / NULLIF(b.base_avg_volume, 0), 1), 2) AS volume_ratio,
                    r.up_days,
                    ROUND(l.close_price, 2) AS latest_close,
                    ROUND(l.change_pct, 2) AS latest_change_pct,
                    (SELECT start_date FROM bounds) AS start_date,
                    (SELECT end_date FROM bounds) AS end_date,
                    ROUND(100 * (
                        0.35 * LEAST(LOG10(GREATEST(r.avg_amount, 0) + 1) / 9, 1) +
                        0.25 * LEAST(GREATEST(r.avg_turnover, 0) / 12, 1) +
                        0.25 * LEAST(GREATEST(COALESCE(r.avg_volume / NULLIF(b.base_avg_volume, 0), 1), 0) / 3, 1) +
                        0.15 * LEAST(GREATEST(r.up_days / NULLIF(r.trade_days, 0), 0), 1)
                    ), 2) AS hot_score
                FROM recent r
                LEFT JOIN latest l ON l.stock_code = r.stock_code AND l.exchange_type = r.exchange_type
                LEFT JOIN baseline b ON b.stock_code = r.stock_code AND b.exchange_type = r.exchange_type
                LEFT JOIN stock_info s ON s.stock_code = r.stock_code AND s.exchange_type = r.exchange_type
                WHERE r.trade_days >= GREATEST(3, FLOOR((SELECT trade_days FROM bounds) * 0.6))
                  AND COALESCE(r.avg_amount, 0) > 0
                ORDER BY hot_score DESC, r.avg_amount DESC
                LIMIT %d
                """.formatted(endDateFilter, days, baselineDays, limit);

        Query query = entityManager.createNativeQuery(sql);
        if (endDate != null) {
            query.setParameter("endDate", endDate);
        }
        List<?> rows = query.getResultList();
        List<Map<String, Object>> items = new ArrayList<>();
        LocalDate startDate = null;
        LocalDate actualEndDate = null;
        for (Object rowObj : rows) {
            Object[] row = (Object[]) rowObj;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stock_code", stringValue(row[0]));
            item.put("stock_name", stringValue(row[1]));
            item.put("exchange_type", stringValue(row[2]));
            item.put("industry", stringValue(row[3]));
            item.put("trade_days", longValue(row[4]));
            item.put("avg_volume", doubleValue(row[5]));
            item.put("avg_amount", doubleValue(row[6]));
            item.put("avg_turnover", doubleValue(row[7]));
            // Q-R008-09：同名 avg_turnover 在回测换手率面是 0–1 的候选替换率，此处是市场换手率百分数（纯增量单位声明）
            item.put("avg_turnover_unit", "PERCENT");
            item.put("avg_turnover_unit_note", "本字段 = market_data.turnover_rate 的窗口均值，单位是百分数（3.5 即 3.5%）；"
                    + "回测换手率面（/api/backtest/turnover）的同名字段是 0–1 的候选替换率，两者不是同一个量");
            item.put("volume_ratio", doubleValue(row[8]));
            item.put("up_days", longValue(row[9]));
            item.put("latest_close", doubleValue(row[10]));
            item.put("latest_change_pct", doubleValue(row[11]));
            startDate = toLocalDate(row[12]);
            actualEndDate = toLocalDate(row[13]);
            double hotScore = doubleValue(row[14]);
            item.put("hot_score", hotScore);
            item.put("hot_level", hotLevel(hotScore));
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("limit", limit);
        result.put("days", days);
        result.put("baseline_days", baselineDays);
        result.put("start_date", startDate != null ? startDate.toString() : "-");
        result.put("end_date", actualEndDate != null ? actualEndDate.toString() : "-");
        result.put("items_count", items.size());
        result.put("formula", "35%成交额规模 + 25%换手率 + 25%量能放大 + 15%上涨天数占比");
        result.put("items", items);
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double doubleValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static String hotLevel(double score) {
        if (score >= 80) return "极热";
        if (score >= 65) return "高热";
        if (score >= 50) return "活跃";
        return "温和";
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

        // 一次批量查询全部候选 stock_info（替代逐条 findByStockCodeAndExchangeType 的 N+1）
        List<String> keys = new java.util.ArrayList<>();
        for (Object[] row : candidates) {
            if (row == null || row.length < 3 || row[0] == null || row[1] == null || row[2] == null) {
                continue;
            }
            keys.add(String.valueOf(row[0]) + "|" + String.valueOf(row[1]));
        }
        Map<String, StockInfo> infoByKey = new HashMap<>();
        if (!keys.isEmpty()) {
            for (StockInfo s : stockInfoRepo.findByStockKeyIn(keys)) {
                infoByKey.put(s.getStockCode() + "|" + s.getExchangeType(), s);
            }
        }
        List<StockInfo> toUpdate = new java.util.ArrayList<>();
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
            StockInfo info = infoByKey.get(stockCode + "|" + exchangeType);
            if (info == null || info.getListDate() != null) {
                skippedNoMatch++;
                continue;
            }
            info.setListDate(earliest);
            toUpdate.add(info);
            updated++;
        }
        if (!toUpdate.isEmpty()) {
            stockInfoRepo.saveAll(toUpdate);
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
