package com.quant.repository;

import com.quant.entity.SelectorResultItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SelectorResultItemRepository extends JpaRepository<SelectorResultItem, Long> {
    List<SelectorResultItem> findByResultIdOrderByRankNoAsc(String resultId);
    void deleteByResultId(String resultId);
}
