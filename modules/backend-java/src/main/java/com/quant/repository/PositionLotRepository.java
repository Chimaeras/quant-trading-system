package com.quant.repository;

import com.quant.entity.PositionLot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 持仓批次（V2-MP-021）。T+1 与摊薄成本按批次计算，与在线账户按 sessionId 隔离。
 */
public interface PositionLotRepository extends JpaRepository<PositionLot, Long> {

    /** 某证券全部批次（含已卖空批次，用于摊薄成本与批次明细）。 */
    List<PositionLot> findByAccountIdAndSessionIdAndStockCodeAndExchangeType(
            Long accountId, Long sessionId, String stockCode, String exchangeType);

    /** 某证券未卖空批次，按建仓交易日升序（卖出 FIFO 消耗顺序）。 */
    List<PositionLot> findByAccountIdAndSessionIdAndStockCodeAndExchangeTypeAndRemainingVolumeGreaterThanOrderByAcquisitionTradeDateAscIdAsc(
            Long accountId, Long sessionId, String stockCode, String exchangeType, int remainingVolumeGreaterThan);

    long countByAccountIdAndSessionIdAndStockCodeAndExchangeType(
            Long accountId, Long sessionId, String stockCode, String exchangeType);
}
