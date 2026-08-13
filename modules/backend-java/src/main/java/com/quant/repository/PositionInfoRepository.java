package com.quant.repository;

import com.quant.entity.PositionInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionInfoRepository extends JpaRepository<PositionInfo, Long> {

    /** 按会话查询持仓（sessionId=0 为在线账户；>0 为历史演练会话，V2-MP-021）。 */
    List<PositionInfo> findByAccountIdAndSessionIdOrderByStockCodeAsc(Long accountId, Long sessionId);

    /** 按会话统计持仓数（避免全量水合仅取 size，V2-MP 性能优化）。 */
    long countByAccountIdAndSessionId(Long accountId, Long sessionId);

    Optional<PositionInfo> findByAccountIdAndSessionIdAndStockCodeAndExchangeType(
            Long accountId, Long sessionId, String stockCode, String exchangeType);
}
