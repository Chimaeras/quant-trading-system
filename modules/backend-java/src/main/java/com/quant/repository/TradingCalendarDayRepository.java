package com.quant.repository;

import com.quant.entity.TradingCalendarDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TradingCalendarDayRepository extends JpaRepository<TradingCalendarDay, Long> {

    Optional<TradingCalendarDay> findByTradeDate(LocalDate tradeDate);

    /** 区间内全部交易日（升序）。 */
    List<TradingCalendarDay> findByTradeDateBetweenOrderByTradeDateAsc(
            LocalDate startDate, LocalDate endDate);

    @Query("SELECT COUNT(t) FROM TradingCalendarDay t")
    long count();

    @Query("SELECT COUNT(t) FROM TradingCalendarDay t WHERE t.source = :source")
    long countBySource(String source);

    @Query("SELECT COUNT(t) FROM TradingCalendarDay t WHERE t.reliability = :reliability")
    long countByReliability(String reliability);

    @Query("SELECT MIN(t.tradeDate) FROM TradingCalendarDay t")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(t.tradeDate) FROM TradingCalendarDay t")
    Optional<LocalDate> findMaxTradeDate();

    /** 区间内交易日集合（用于与 market_data 对比计算缺失交易日）。 */
    @Query("SELECT t.tradeDate FROM TradingCalendarDay t " +
            "WHERE t.tradeDate BETWEEN :start AND :end ORDER BY t.tradeDate ASC")
    List<LocalDate> findTradeDatesBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
