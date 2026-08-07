package com.quant.repository;

import com.quant.entity.IndexQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IndexQuoteRepository extends JpaRepository<IndexQuote, Long> {

    Optional<IndexQuote> findByIndexCodeAndTradeDate(String indexCode, LocalDate tradeDate);

    List<IndexQuote> findByIndexCodeAndTradeDateBetweenOrderByTradeDateAsc(
            String indexCode, LocalDate startDate, LocalDate endDate);

    @Query("SELECT COUNT(i) FROM IndexQuote i")
    long count();

    @Query("SELECT COUNT(DISTINCT i.indexCode) FROM IndexQuote i")
    long countDistinctIndices();

    @Query("SELECT COUNT(i) FROM IndexQuote i WHERE i.source = :source")
    long countBySource(String source);

    @Query("SELECT COUNT(i) FROM IndexQuote i WHERE i.reliability = :reliability")
    long countByReliability(String reliability);

    @Query("SELECT DISTINCT i.indexCode FROM IndexQuote i")
    List<String> findDistinctIndexCodes();

    @Query("SELECT MIN(i.tradeDate) FROM IndexQuote i WHERE i.indexCode = :code")
    Optional<LocalDate> findMinTradeDateByIndex(@Param("code") String indexCode);

    @Query("SELECT MAX(i.tradeDate) FROM IndexQuote i WHERE i.indexCode = :code")
    Optional<LocalDate> findMaxTradeDateByIndex(@Param("code") String indexCode);

    @Query("SELECT COUNT(i) FROM IndexQuote i WHERE i.indexCode = :code")
    long countByIndex(@Param("code") String indexCode);

    @Query("SELECT MIN(i.tradeDate) FROM IndexQuote i")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(i.tradeDate) FROM IndexQuote i")
    Optional<LocalDate> findMaxTradeDate();

    /** 内容指纹：count, minDate, maxDate, sum(close), sum(volume), max(ingested_at)。 */
    @Query("""
            SELECT COUNT(i), MIN(i.tradeDate), MAX(i.tradeDate),
                   COALESCE(SUM(i.closePrice), 0), COALESCE(SUM(i.volume), 0), MAX(i.ingestedAt)
            FROM IndexQuote i
            """)
    List<Object[]> aggregateContentFingerprint();
}
