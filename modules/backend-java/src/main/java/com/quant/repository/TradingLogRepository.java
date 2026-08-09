package com.quant.repository;

import com.quant.entity.TradingLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TradingLogRepository extends JpaRepository<TradingLog, Long> {

    /** 按会话查询最近成交记录（按时间倒序，供账户订单历史；sessionId=0 为在线账户）。 */
    List<TradingLog> findTop50ByAccountIdAndSessionIdOrderByTradeTimeDesc(Long accountId, Long sessionId);

    /** 会话内最晚成交时间（用于推导历史演练 as-of 交易日）。 */
    @Query("SELECT MAX(t.tradeTime) FROM TradingLog t WHERE t.accountId = :accountId AND t.sessionId = :sessionId")
    Optional<LocalDateTime> maxTradeTimeByAccountAndSession(
            @Param("accountId") Long accountId, @Param("sessionId") Long sessionId);
}
