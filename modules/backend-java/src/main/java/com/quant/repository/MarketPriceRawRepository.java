package com.quant.repository;

import com.quant.entity.MarketPriceRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketPriceRawRepository extends JpaRepository<MarketPriceRaw, Long> {

    Optional<MarketPriceRaw> findByStockCodeAndExchangeTypeAndTradeDate(
            String stockCode, String exchangeType, LocalDate tradeDate);

    List<MarketPriceRaw> findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
            String stockCode, String exchangeType, LocalDate startDate, LocalDate endDate);

    long countByStockCodeAndExchangeTypeAndTradeDateBetween(
            String stockCode, String exchangeType, LocalDate startDate, LocalDate endDate);

    /** 某交易日全市场原始行情（标签工厂按日历日批量查找）。 */
    List<MarketPriceRaw> findByTradeDate(LocalDate tradeDate);

    List<MarketPriceRaw> findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
            LocalDate startDate, LocalDate endDate);

    @Query("SELECT COUNT(m) FROM MarketPriceRaw m")
    long countAll();

    @Query("SELECT COUNT(DISTINCT m.stockCode || '|' || m.exchangeType) FROM MarketPriceRaw m")
    long countDistinctStocks();

    @Query("SELECT COUNT(DISTINCT m.tradeDate) FROM MarketPriceRaw m")
    long countDistinctTradeDates();

    /** 区间内不复权行情实际覆盖的交易日数（MP-006 区间覆盖检查）。 */
    @Query("SELECT COUNT(DISTINCT m.tradeDate) FROM MarketPriceRaw m WHERE m.tradeDate BETWEEN :start AND :end")
    long countDistinctTradeDatesBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 区间内不复权行情覆盖的（股票|交易所）对数量（MP-006 区间覆盖检查）。 */
    @Query("SELECT COUNT(DISTINCT m.stockCode || '|' || m.exchangeType) FROM MarketPriceRaw m WHERE m.tradeDate BETWEEN :start AND :end")
    long countDistinctStocksBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT MAX(m.tradeDate) FROM MarketPriceRaw m")
    Optional<LocalDate> findMaxTradeDate();

    @Query("SELECT MIN(m.tradeDate) FROM MarketPriceRaw m")
    Optional<LocalDate> findMinTradeDate();

    /**
     * 内容指纹聚合：count, minDate, maxDate, sum(close), sum(volume), sum(close*volume), max(ingested_at)。
     */
    @Query("""
            SELECT COUNT(m), MIN(m.tradeDate), MAX(m.tradeDate),
                   COALESCE(SUM(m.closePrice), 0), COALESCE(SUM(m.volume), 0),
                   COALESCE(SUM(m.closePrice * COALESCE(m.volume, 0)), 0), MAX(m.ingestedAt)
            FROM MarketPriceRaw m
            """)
    List<Object[]> aggregateContentFingerprint();
}
