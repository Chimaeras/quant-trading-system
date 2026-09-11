package com.quant.repository;

import com.quant.entity.PositionInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PositionInfoRepository extends JpaRepository<PositionInfo, Long> {

    /** 按会话查询持仓（sessionId=0 为在线账户；>0 为历史演练会话，V2-MP-021）。 */
    List<PositionInfo> findByAccountIdAndSessionIdOrderByStockCodeAsc(Long accountId, Long sessionId);

    /** 按会话统计持仓数（避免全量水合仅取 size，V2-MP 性能优化）。 */
    long countByAccountIdAndSessionId(Long accountId, Long sessionId);

    Optional<PositionInfo> findByAccountIdAndSessionIdAndStockCodeAndExchangeType(
            Long accountId, Long sessionId, String stockCode, String exchangeType);

    /**
     * 刷新行情衍生的展示列，只覆盖这四列。
     *
     * <p>与 {@code UserAccountRepository.updateDisplayMetrics} 同因：概览接口读到的是
     * 游离态持仓，整实体 merge 会把 volume/avgCost/availableVolume/firstBuyDate 等
     * 「账务列」按读取那一刻的值写回，覆盖并发下单/重算的结果（丢更新 → 持仓数量回退）。
     */
    @Transactional
    @Modifying
    @Query("UPDATE PositionInfo p SET p.currentPrice = :currentPrice, p.marketValue = :marketValue, "
            + "p.profitLoss = :profitLoss, p.profitRate = :profitRate WHERE p.id = :id")
    int updateQuoteMetrics(@Param("id") Long id,
                           @Param("currentPrice") BigDecimal currentPrice,
                           @Param("marketValue") BigDecimal marketValue,
                           @Param("profitLoss") BigDecimal profitLoss,
                           @Param("profitRate") BigDecimal profitRate);

    /** T+1 可卖量是批次推导值，同样只定向更新该列（理由见 {@link #updateQuoteMetrics}）。 */
    @Transactional
    @Modifying
    @Query("UPDATE PositionInfo p SET p.availableVolume = :availableVolume WHERE p.id = :id")
    int updateAvailableVolume(@Param("id") Long id, @Param("availableVolume") Integer availableVolume);
}
