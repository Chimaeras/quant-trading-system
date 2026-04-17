package com.quant.repository;

import com.quant.entity.SelectorResultItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SelectorResultItemRepository extends JpaRepository<SelectorResultItem, Long> {
    List<SelectorResultItem> findByResultIdOrderByRankNoAsc(String resultId);
    Page<SelectorResultItem> findByResultIdOrderByRankNoAsc(String resultId, Pageable pageable);
    void deleteByResultId(String resultId);
}
