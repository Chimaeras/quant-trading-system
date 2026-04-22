package com.quant.repository;

import com.quant.entity.UnifiedScoringTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface UnifiedScoringTaskRepository extends JpaRepository<UnifiedScoringTask, Long> {
    Optional<UnifiedScoringTask> findByTaskId(String taskId);

    long countByStatus(String status);

    long countByStatusAndCreatedAtBefore(String status, LocalDateTime createdAt);

    List<UnifiedScoringTask> findByStatusOrderByCreatedAtAsc(String status);

    List<UnifiedScoringTask> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(String status, LocalDateTime createdAt);

    Optional<UnifiedScoringTask> findFirstByStrategyAndRequestFingerprintOrderByCreatedAtDesc(String strategy, String requestFingerprint);

    Optional<UnifiedScoringTask> findFirstByStrategyAndRequestFingerprintAndStatusInOrderByCreatedAtDesc(
            String strategy, String requestFingerprint, Collection<String> status
    );
}
