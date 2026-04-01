package com.quant.repository;

import com.quant.entity.SelectorResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface SelectorResultRepository extends JpaRepository<SelectorResult, Long> {
    Optional<SelectorResult> findFirstByTradeDateOrderByCreatedAtDesc(LocalDate tradeDate);

    Optional<SelectorResult> findFirstByTradeDateAndFactorVersionOrderByCreatedAtDesc(LocalDate tradeDate, String factorVersion);

    Optional<SelectorResult> findByResultId(String resultId);
}
