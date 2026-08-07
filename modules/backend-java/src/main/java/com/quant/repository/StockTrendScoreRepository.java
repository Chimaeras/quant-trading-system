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

    void deleteByTradeDateAndScoreType(LocalDate tradeDate, String scoreType);

    Page<StockTrendScore> findByTradeDateAndScoreTypeOrderByTotalScoreDesc(
            LocalDate tradeDate, String scoreType, Pageable pageable);

    @Query("SELECT MAX(s.tradeDate) FROM StockTrendScore s WHERE s.scoreType = :scoreType")
    Optional<LocalDate> findMaxTradeDateByScoreType(@Param("scoreType") String scoreType);

    @Query("SELECT MAX(s.tradeDate) FROM StockTrendScore s WHERE s.scoreType = :scoreType AND s.tradeDate <= :tradeDate")
    Optional<LocalDate> findMaxTradeDateOnOrBeforeByScoreType(
            @Param("scoreType") String scoreType, @Param("tradeDate") LocalDate tradeDate);

    long countByTradeDateAndScoreType(LocalDate tradeDate, String scoreType);
}
