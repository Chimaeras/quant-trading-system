package com.quant.repository;

import com.quant.entity.SecurityMasterHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SecurityMasterHistoryRepository extends JpaRepository<SecurityMasterHistory, Long> {

    /**
     * Point-in-time 查询：返回某证券在 as_of 日生效的记录（valid_from <= as_of < valid_to）。
     * valid_to 为 null 表示至今有效。不返回该日之后才生效的记录，消除前视风险。
     */
    @Query("SELECT h FROM SecurityMasterHistory h " +
            "WHERE h.stockCode = :stockCode AND h.exchangeType = :exchangeType " +
            "AND h.validFrom <= :asOf AND (h.validTo IS NULL OR h.validTo > :asOf)")
    Optional<SecurityMasterHistory> findEffectiveAt(
            @Param("stockCode") String stockCode,
            @Param("exchangeType") String exchangeType,
            @Param("asOf") LocalDate asOf);

    /** 批量 PIT 查询：某日生效的全部证券主数据（用于回测/重放时构建历史时点股票池）。 */
    @Query("SELECT h FROM SecurityMasterHistory h " +
            "WHERE h.validFrom <= :asOf AND (h.validTo IS NULL OR h.validTo > :asOf)")
    List<SecurityMasterHistory> findAllEffectiveAt(@Param("asOf") LocalDate asOf);

    /** 是否已存在某 (stock_code, exchange_type, valid_from) 记录（初始化幂等检查）。 */
    Optional<SecurityMasterHistory> findByStockCodeAndExchangeTypeAndValidFrom(
            String stockCode, String exchangeType, LocalDate validFrom);

    @Query("SELECT COUNT(DISTINCT h.stockCode || '|' || h.exchangeType) FROM SecurityMasterHistory h")
    long countDistinctStocks();

    @Query("SELECT COUNT(h) FROM SecurityMasterHistory h WHERE h.source = :source")
    long countBySource(String source);
}
