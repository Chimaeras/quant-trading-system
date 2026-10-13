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

    /**
     * 区间内交易日集合（用于与 market_data 对比计算缺失交易日）。
     *
     * <p>D-CAL-01（2026-10-13 修正）：此前不过滤 {@code is_trading_day}。表结构允许写入
     * {@code is_trading_day=false} 的行（实体默认 true 且 {@code @Column(nullable=false)}），
     * 一旦存在这类行，凡以此方法作为「交易日集合」的地方都会把**非交易日**当交易日，
     * 进而把「该日无行情」误报为缺失数据、并让标签/回测的日轴出现空日。
     */
    @Query("SELECT t.tradeDate FROM TradingCalendarDay t " +
            "WHERE t.tradeDate BETWEEN :start AND :end AND t.isTradingDay = true ORDER BY t.tradeDate ASC")
    List<LocalDate> findTradeDatesBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 内容指纹：count, minDate, maxDate, max(ingested_at)。 */
    @Query("""
            SELECT COUNT(t), MIN(t.tradeDate), MAX(t.tradeDate), MAX(t.ingestedAt)
            FROM TradingCalendarDay t
            """)
    List<Object[]> aggregateContentFingerprint();
}
