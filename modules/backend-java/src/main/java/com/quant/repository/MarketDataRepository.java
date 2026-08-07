package com.quant.repository;

import com.quant.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketDataRepository extends JpaRepository<MarketData, Long> {

    List<MarketData> findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
            String stockCode, String exchangeType, LocalDate startDate, LocalDate endDate);

    long countByStockCodeAndExchangeTypeAndTradeDateBetween(
            String stockCode, String exchangeType, LocalDate startDate, LocalDate endDate);

    Optional<MarketData> findByStockCodeAndExchangeTypeAndTradeDate(
            String stockCode, String exchangeType, LocalDate tradeDate);

    Optional<MarketData> findFirstByStockCodeAndExchangeTypeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
            String stockCode, String exchangeType, LocalDate tradeDate);

    Optional<MarketData> findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(
            String stockCode, String exchangeType);

    /** 指定股票在 tradeDate 之前的最近一条行情（用于推导前收，跳过非交易日）。 */
    Optional<MarketData> findFirstByStockCodeAndExchangeTypeAndTradeDateLessThanOrderByTradeDateDesc(
            String stockCode, String exchangeType, LocalDate tradeDate);

    @Query("SELECT MIN(m.tradeDate) FROM MarketData m")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(m.tradeDate) FROM MarketData m")
    Optional<LocalDate> findMaxTradeDate();

    /**
     * 内容指纹聚合：count, minDate, maxDate, sum(close), sum(volume), sum(close*volume), max(updated_at)。
     */
    @Query("""
            SELECT COUNT(m), MIN(m.tradeDate), MAX(m.tradeDate),
                   COALESCE(SUM(m.closePrice), 0), COALESCE(SUM(m.volume), 0),
                   COALESCE(SUM(m.closePrice * COALESCE(m.volume, 0)), 0), MAX(m.updatedAt)
            FROM MarketData m
            """)
    List<Object[]> aggregateContentFingerprint();

    @Query("SELECT MAX(m.tradeDate) FROM MarketData m WHERE m.tradeDate <= :tradeDate")
    Optional<LocalDate> findLatestTradeDateOnOrBefore(@Param("tradeDate") LocalDate tradeDate);

    @Query(value = "SELECT COUNT(*) FROM (SELECT DISTINCT stock_code, exchange_type FROM market_data) t", nativeQuery = true)
    long countDistinctStocks();

    @Query(value = "SELECT DISTINCT stock_code, exchange_type FROM market_data WHERE trade_date BETWEEN :startDate AND :endDate", nativeQuery = true)
    List<Object[]> findDistinctStockPairsInRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * 二阶段优化：一次性批量拉取区间内所有行情（用于在应用层按股票分组计算，减少 N+1 查询）。
     * 注意：数据量可能较大，应结合 lookbackDays 控制窗口大小。
     */
    List<MarketData> findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(LocalDate startDate, LocalDate endDate);

    /** 某日全市场快照（用于行业 PE 聚合等） */
    List<MarketData> findByTradeDate(LocalDate tradeDate);

    /** 查询某股票在日期范围内已存在的交易日列表（用于去重） */
    @Query("SELECT m.tradeDate FROM MarketData m WHERE m.stockCode = :stockCode AND m.exchangeType = :exchangeType AND m.tradeDate BETWEEN :startDate AND :endDate")
    List<LocalDate> findExistingTradeDates(@Param("stockCode") String stockCode, @Param("exchangeType") String exchangeType,
                                           @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /** 获取区间内所有不重复的交易日（升序），用于回测时定位 T+N 交易日 */
    @Query("SELECT DISTINCT m.tradeDate FROM MarketData m WHERE m.tradeDate BETWEEN :start AND :end ORDER BY m.tradeDate")
    List<LocalDate> findDistinctTradeDatesBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** stock_info.list_date 缺失时，用 market_data 最早交易日批量回填候选 */
    @Query(value = """
            SELECT m.stock_code, m.exchange_type, MIN(m.trade_date)
            FROM market_data m
            INNER JOIN stock_info s
                ON s.stock_code = m.stock_code AND s.exchange_type = m.exchange_type
            WHERE s.list_date IS NULL
            GROUP BY m.stock_code, m.exchange_type
            """, nativeQuery = true)
    List<Object[]> findEarliestTradeDatesForMissingListDate();

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT 1
                FROM market_data m
                INNER JOIN stock_info s
                    ON s.stock_code = m.stock_code AND s.exchange_type = m.exchange_type
                WHERE s.list_date IS NULL
                GROUP BY m.stock_code, m.exchange_type
            ) t
            """, nativeQuery = true)
    long countBackfillableListDateCandidates();

    /**
     * 批量拉取多个证券在区间内的行情（按 "code|exchange" key 匹配），
     * 用于自选股扫描等按股票分组场景，替代逐股 N+1 查询。
     */
    @Query("""
            select m from MarketData m
            where concat(m.stockCode, '|', m.exchangeType) in :keys
              and m.tradeDate between :startDate and :endDate
            order by m.tradeDate asc
            """)
    List<MarketData> findByStockKeyInAndTradeDateBetween(@Param("keys") List<String> keys,
                                                         @Param("startDate") LocalDate startDate,
                                                         @Param("endDate") LocalDate endDate);

    /** 区间内各证券最早交易日（用于 bulkFetch=false 时上市时长 pair 预过滤）。 */
    @Query(value = """
            SELECT m.stock_code, m.exchange_type, MIN(m.trade_date)
            FROM market_data m
            WHERE m.trade_date BETWEEN :startDate AND :endDate
            GROUP BY m.stock_code, m.exchange_type
            """, nativeQuery = true)
    List<Object[]> findEarliestTradeDatesInRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
