package com.quant.repository;

import com.quant.entity.StockMultiDimScore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface StockMultiDimScoreRepository extends JpaRepository<StockMultiDimScore, Long> {

    void deleteByTradeDateAndFactorVersion(LocalDate tradeDate, String factorVersion);

    Page<StockMultiDimScore> findByTradeDateAndFactorVersionOrderByCompositeScoreDesc(
            LocalDate tradeDate, String factorVersion, Pageable pageable);

    @Query("SELECT MAX(s.tradeDate) FROM StockMultiDimScore s WHERE s.factorVersion = :fv")
    Optional<LocalDate> findMaxTradeDate(@Param("fv") String factorVersion);

    @Query("SELECT MAX(s.tradeDate) FROM StockMultiDimScore s WHERE s.factorVersion = :fv AND s.tradeDate <= :d")
    Optional<LocalDate> findMaxTradeDateOnOrBefore(@Param("fv") String factorVersion, @Param("d") LocalDate d);
}
