package com.quant.repository;

import com.quant.entity.SelectorResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface SelectorResultRepository extends JpaRepository<SelectorResult, Long> {
    Optional<SelectorResult> findFirstByTradeDateOrderByCreatedAtDesc(LocalDate tradeDate);

    Optional<SelectorResult> findFirstByTradeDateAndFactorVersionOrderByCreatedAtDesc(LocalDate tradeDate, String factorVersion);

    Optional<SelectorResult> findByResultId(String resultId);

    Optional<SelectorResult> findFirstByTradeDateAndStrategyTypeOrderByCreatedAtDesc(LocalDate tradeDate, String strategyType);

    Optional<SelectorResult> findFirstByStrategyTypeOrderByCreatedAtDesc(String strategyType);

    @Query("SELECT MAX(s.tradeDate) FROM SelectorResult s WHERE s.strategyType = :strategy")
    Optional<LocalDate> findMaxTradeDateByStrategy(@Param("strategy") String strategyType);

    Page<SelectorResult> findByStrategyTypeOrderByCreatedAtDesc(String strategyType, Pageable pageable);
}
