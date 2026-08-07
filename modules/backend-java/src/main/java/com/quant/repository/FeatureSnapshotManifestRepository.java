package com.quant.repository;

import com.quant.entity.FeatureSnapshotManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FeatureSnapshotManifestRepository extends JpaRepository<FeatureSnapshotManifest, Long> {

    Optional<FeatureSnapshotManifest> findBySnapshotIdAndSignalDateAndStrategyVersionAndFeatureSetVersion(
            String snapshotId, LocalDate signalDate, String strategyVersion, String featureSetVersion);

    List<FeatureSnapshotManifest> findBySnapshotIdAndStrategyVersionAndFeatureSetVersionAndSignalDateBetweenOrderBySignalDateAsc(
            String snapshotId, String strategyVersion, String featureSetVersion,
            LocalDate start, LocalDate end);
}
