package com.quant.repository;

import com.quant.entity.TradingLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TradingLogRepository extends JpaRepository<TradingLog, Long> {

    /** 最近成交记录（按时间倒序，供账户订单历史）。 */
    List<TradingLog> findTop50ByAccountIdOrderByTradeTimeDesc(Long accountId);

    @Query("""
            SELECT COALESCE(SUM(t.tradeVolume), 0) FROM TradingLog t
            WHERE t.accountId = :accountId
              AND t.stockCode = :stockCode
              AND t.exchangeType = :exchangeType
              AND t.tradeType = 1
              AND t.tradeTime >= :start
              AND t.tradeTime < :end
            """)
    int sumBuyVolumeBetween(
            @Param("accountId") Long accountId,
            @Param("stockCode") String stockCode,
            @Param("exchangeType") String exchangeType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
}
