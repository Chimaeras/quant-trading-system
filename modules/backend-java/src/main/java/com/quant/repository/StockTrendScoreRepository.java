package com.quant.repository;

import com.quant.entity.StockTrendScore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface StockTrendScoreRepository extends JpaRepository<StockTrendScore, Long> {

    void deleteByTradeDate(LocalDate tradeDate);

    Page<StockTrendScore> findByTradeDateOrderByTotalScoreDesc(LocalDate tradeDate, Pageable pageable);

    @Query("SELECT MAX(s.tradeDate) FROM StockTrendScore s")
    Optional<LocalDate> findMaxTradeDate();

    @Query("SELECT MAX(s.tradeDate) FROM StockTrendScore s WHERE s.tradeDate <= :tradeDate")
    Optional<LocalDate> findMaxTradeDateOnOrBefore(@Param("tradeDate") LocalDate tradeDate);

    long countByTradeDate(LocalDate tradeDate);
}
