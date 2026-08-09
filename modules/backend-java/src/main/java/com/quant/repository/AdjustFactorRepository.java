package com.quant.repository;

import com.quant.entity.AdjustFactor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AdjustFactorRepository extends JpaRepository<AdjustFactor, Long> {

    Optional<AdjustFactor> findByStockCodeAndExchangeTypeAndTradeDate(
            String stockCode, String exchangeType, LocalDate tradeDate);

    List<AdjustFactor> findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
            String stockCode, String exchangeType, LocalDate startDate, LocalDate endDate);

    long countByStockCodeAndExchangeTypeAndTradeDateBetween(
            String stockCode, String exchangeType, LocalDate startDate, LocalDate endDate);

    @Query("SELECT COUNT(a) FROM AdjustFactor a")
    long countAll();

    @Query("SELECT COUNT(DISTINCT a.stockCode || '|' || a.exchangeType) FROM AdjustFactor a")
    long countDistinctStocks();

    @Query("SELECT COUNT(a) FROM AdjustFactor a WHERE a.source = :source")
    long countBySource(String source);

    @Query("SELECT COUNT(a) FROM AdjustFactor a WHERE a.reliability = :reliability")
    long countByReliability(String reliability);

    /** 区间内复权因子实际覆盖的交易日数（MP-006 区间覆盖检查）。 */
    @Query("SELECT COUNT(DISTINCT a.tradeDate) FROM AdjustFactor a WHERE a.tradeDate BETWEEN :start AND :end")
    long countDistinctTradeDatesBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 区间内复权因子覆盖的（股票|交易所）对数量（MP-006 区间覆盖检查）。 */
    @Query("SELECT COUNT(DISTINCT a.stockCode || '|' || a.exchangeType) FROM AdjustFactor a WHERE a.tradeDate BETWEEN :start AND :end")
    long countDistinctStocksBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT MIN(a.tradeDate) FROM AdjustFactor a")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(a.tradeDate) FROM AdjustFactor a")
    Optional<LocalDate> findMaxTradeDate();

    /** 内容指纹：count, minDate, maxDate, sum(factor), max(ingested_at)。 */
    @Query("""
            SELECT COUNT(a), MIN(a.tradeDate), MAX(a.tradeDate),
                   COALESCE(SUM(a.factor), 0), MAX(a.ingestedAt)
            FROM AdjustFactor a
            """)
    List<Object[]> aggregateContentFingerprint();
}
