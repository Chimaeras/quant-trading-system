package com.quant.repository;

import com.quant.entity.LabelSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LabelSnapshotRepository extends JpaRepository<LabelSnapshot, Long> {

    Optional<LabelSnapshot> findByLabelVersionAndSignalDateAndStockCodeAndExchangeTypeAndHorizon(
            String labelVersion, LocalDate signalDate, String stockCode, String exchangeType, Integer horizon);

    @Query("SELECT COUNT(l) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    long countByVersion(@Param("version") String labelVersion);

    @Query("SELECT COUNT(l) FROM LabelSnapshot l WHERE l.labelVersion = :version AND l.execStatus = :status")
    long countByVersionAndExecStatus(@Param("version") String labelVersion, @Param("status") String execStatus);

    @Query("SELECT COUNT(DISTINCT l.signalDate) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    long countDistinctSignalDates(@Param("version") String labelVersion);

    @Query("SELECT MIN(l.signalDate) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    Optional<LocalDate> findMinSignalDate(@Param("version") String labelVersion);

    @Query("SELECT MAX(l.signalDate) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    Optional<LocalDate> findMaxSignalDate(@Param("version") String labelVersion);

    @Query("SELECT l FROM LabelSnapshot l WHERE l.labelVersion = :version ORDER BY l.signalDate DESC")
    List<LabelSnapshot> findLatestByVersion(@Param("version") String labelVersion, int limit);
}
