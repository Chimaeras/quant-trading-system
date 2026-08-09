package com.quant.repository;

import com.quant.entity.FeatureSnapshotRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 特征快照逐证券行（V2-MP-016）。
 *
 * <p>分区查询/删除均按 manifest 同一幂等键；删除用于 FAILED 分区重算前的清场（避免残留行被当幂等成功）。
 */
public interface FeatureSnapshotRowRepository extends JpaRepository<FeatureSnapshotRow, Long> {

    List<FeatureSnapshotRow> findBySnapshotIdAndSignalDateAndStrategyVersionAndFeatureSetVersion(
            String snapshotId, LocalDate signalDate, String strategyVersion, String featureSetVersion);

    long countBySnapshotIdAndSignalDateAndStrategyVersionAndFeatureSetVersion(
            String snapshotId, LocalDate signalDate, String strategyVersion, String featureSetVersion);

    @Modifying
    @Transactional
    @Query("DELETE FROM FeatureSnapshotRow r WHERE r.snapshotId = :snapshotId "
            + "AND r.signalDate = :signalDate AND r.strategyVersion = :strategyVersion "
            + "AND r.featureSetVersion = :featureSetVersion")
    int deletePartition(
            @Param("snapshotId") String snapshotId,
            @Param("signalDate") LocalDate signalDate,
            @Param("strategyVersion") String strategyVersion,
            @Param("featureSetVersion") String featureSetVersion);

    /** 分区完整候选截面（训练/IC 消费）。 */
    @Query("SELECT r FROM FeatureSnapshotRow r WHERE r.snapshotId = :snapshotId "
            + "AND r.signalDate = :signalDate AND r.strategyVersion = :strategyVersion "
            + "AND r.featureSetVersion = :featureSetVersion ORDER BY r.rankNo ASC")
    List<FeatureSnapshotRow> listPartition(
            @Param("snapshotId") String snapshotId,
            @Param("signalDate") LocalDate signalDate,
            @Param("strategyVersion") String strategyVersion,
            @Param("featureSetVersion") String featureSetVersion);
}
