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

    Optional<MarketData> findByStockCodeAndExchangeTypeAndTradeDate(
            String stockCode, String exchangeType, LocalDate tradeDate);

    Optional<MarketData> findFirstByStockCodeAndExchangeTypeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
            String stockCode, String exchangeType, LocalDate tradeDate);

    Optional<MarketData> findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(
            String stockCode, String exchangeType);

    @Query("SELECT MIN(m.tradeDate) FROM MarketData m")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(m.tradeDate) FROM MarketData m")
    Optional<LocalDate> findMaxTradeDate();

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
}
