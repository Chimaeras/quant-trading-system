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

    Optional<MarketData> findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(
            String stockCode, String exchangeType);

    @Query("SELECT MIN(m.tradeDate) FROM MarketData m")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(m.tradeDate) FROM MarketData m")
    Optional<LocalDate> findMaxTradeDate();

    @Query(value = "SELECT COUNT(*) FROM (SELECT DISTINCT stock_code, exchange_type FROM market_data) t", nativeQuery = true)
    long countDistinctStocks();

    /** 查询某股票在日期范围内已存在的交易日列表（用于去重） */
    @Query("SELECT m.tradeDate FROM MarketData m WHERE m.stockCode = :stockCode AND m.exchangeType = :exchangeType AND m.tradeDate BETWEEN :startDate AND :endDate")
    List<LocalDate> findExistingTradeDates(@Param("stockCode") String stockCode, @Param("exchangeType") String exchangeType,
                                           @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
