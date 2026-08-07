package com.quant.repository;

import com.quant.entity.PredictionSnapshotItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PredictionSnapshotItemRepository extends JpaRepository<PredictionSnapshotItem, Long> {

    List<PredictionSnapshotItem> findByRunIdOrderByRankNoAsc(String runId);

    List<PredictionSnapshotItem> findByRunIdInOrderByRankNoAsc(List<String> runIds);
}
