package com.quant.repository;

import com.quant.entity.SelectorResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SelectorResultRepository extends JpaRepository<SelectorResult, Long> {
    Optional<SelectorResult> findFirstByTradeDateOrderByCreatedAtDesc(LocalDate tradeDate);

    Optional<SelectorResult> findFirstByTradeDateAndFactorVersionOrderByCreatedAtDesc(LocalDate tradeDate, String factorVersion);

    Optional<SelectorResult> findByResultId(String resultId);

    Optional<SelectorResult> findFirstByTradeDateAndStrategyTypeOrderByCreatedAtDesc(LocalDate tradeDate, String strategyType);

    Optional<SelectorResult> findFirstByStrategyTypeOrderByCreatedAtDesc(String strategyType);

    Optional<SelectorResult> findFirstByStrategyTypeAndRequestFingerprintOrderByCreatedAtDesc(
            String strategyType, String requestFingerprint
    );

    Optional<SelectorResult> findFirstByStrategyTypeAndRequestFingerprintAndRunStatusInOrderByCreatedAtDesc(
            String strategyType, String requestFingerprint, Collection<String> runStatus
    );

    /**
     * 同策略 + 同请求指纹 + 同交易日的最新成功结果。
     *
     * <p>用于统一打分落库的<b>幂等去重</b>：{@code persistUnified} 每次都生成新的
     * {@code resultId}，若不做这层查找，同一个请求重复执行就会不断插入新行——
     * 这正是生产库中 {@code (strategy, date, stock)} 重复率达 41% 的成因。
     *
     * <p>加上 {@code tradeDate} 是必须的：指纹由请求参数构成、<b>不含</b>交易日，
     * 只按 {@code (strategy, fingerprint)} 查会把不同交易日的结果误判为同一次运行。
     */
    Optional<SelectorResult> findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
            String strategyType, String requestFingerprint, LocalDate tradeDate, String runStatus
    );

    @Query("SELECT MAX(s.tradeDate) FROM SelectorResult s WHERE s.strategyType = :strategy")
    Optional<LocalDate> findMaxTradeDateByStrategy(@Param("strategy") String strategyType);

    Page<SelectorResult> findByStrategyTypeOrderByCreatedAtDesc(String strategyType, Pageable pageable);

    @Query("SELECT s FROM SelectorResult s WHERE s.tradeDate BETWEEN :start AND :end ORDER BY s.createdAt DESC")
    List<SelectorResult> findByTradeDateBetweenOrderByCreatedAtDesc(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT DISTINCT s.strategyType FROM SelectorResult s WHERE s.strategyType IS NOT NULL ORDER BY s.strategyType")
    List<String> findDistinctStrategyTypes();

    /**
     * 各策略在指定交易日的成功结果（同策略同日可能多条，取 createdAt 最新一条由 Service 层去重）。
     */
    @Query("SELECT s FROM SelectorResult s WHERE s.tradeDate = :tradeDate AND s.runStatus = 'SUCCESS' ORDER BY s.strategyType, s.createdAt DESC")
    List<SelectorResult> findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(@Param("tradeDate") LocalDate tradeDate);

    /**
     * 各策略最新交易日（不限定状态），用于日报默认数据日期。
     */
    @Query("SELECT s FROM SelectorResult s WHERE s.runStatus = 'SUCCESS' AND s.tradeDate = " +
            "(SELECT MAX(s2.tradeDate) FROM SelectorResult s2 WHERE s2.strategyType = s.strategyType AND s2.runStatus = 'SUCCESS') " +
            "ORDER BY s.strategyType, s.createdAt DESC")
    List<SelectorResult> findLatestSuccessPerStrategy();
}
