package com.quant.repository;

import com.quant.entity.DataSnapshotPartitionManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSnapshotPartitionManifestRepository extends JpaRepository<DataSnapshotPartitionManifest, Long> {

    List<DataSnapshotPartitionManifest> findBySnapshotId(String snapshotId);

    long countBySnapshotId(String snapshotId);
}
