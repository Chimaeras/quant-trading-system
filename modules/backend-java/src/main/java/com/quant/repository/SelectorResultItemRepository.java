package com.quant.repository;

import com.quant.entity.SelectorResultItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Set;

public interface SelectorResultItemRepository extends JpaRepository<SelectorResultItem, Long> {
    List<SelectorResultItem> findByResultIdOrderByRankNoAsc(String resultId);
    Page<SelectorResultItem> findByResultIdOrderByRankNoAsc(String resultId, Pageable pageable);
    void deleteByResultId(String resultId);
    Optional<SelectorResultItem> findByResultIdAndStockCodeAndExchangeType(String resultId, String stockCode, String exchangeType);
    long countByResultId(String resultId);

    @Query("select concat(i.stockCode, '|', i.exchangeType) from SelectorResultItem i where i.resultId = :resultId")
    Set<String> findStockKeysByResultId(@Param("resultId") String resultId);
}
