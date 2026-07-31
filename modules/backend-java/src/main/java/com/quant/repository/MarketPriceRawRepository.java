package com.quant.repository;

import com.quant.entity.MarketPriceRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketPriceRawRepository extends JpaRepository<MarketPriceRaw, Long> {

    Optional<MarketPriceRaw> findByStockCodeAndExchangeTypeAndTradeDate(
            String stockCode, String exchangeType, LocalDate tradeDate);

    List<MarketPriceRaw> findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
            String stockCode, String exchangeType, LocalDate startDate, LocalDate endDate);

    @Query("SELECT COUNT(m) FROM MarketPriceRaw m")
    long countAll();

    @Query("SELECT COUNT(DISTINCT m.stockCode || '|' || m.exchangeType) FROM MarketPriceRaw m")
    long countDistinctStocks();

    @Query("SELECT COUNT(DISTINCT m.tradeDate) FROM MarketPriceRaw m")
    long countDistinctTradeDates();

    @Query("SELECT MAX(m.tradeDate) FROM MarketPriceRaw m")
    Optional<LocalDate> findMaxTradeDate();

    @Query("SELECT MIN(m.tradeDate) FROM MarketPriceRaw m")
    Optional<LocalDate> findMinTradeDate();
}
