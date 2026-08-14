package com.quant.repository;

import com.quant.entity.DataSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DataSnapshotRepository extends JpaRepository<DataSnapshot, Long> {

    List<DataSnapshot> findBySnapshotId(String snapshotId);

    /** 同 cutoff 日 + 同内容 hash → 幂等返回既有快照（V2-MP-030）。 */
    Optional<DataSnapshot> findFirstByCutoffTimeAndHash(LocalDate cutoffTime, String hash);

    List<DataSnapshot> findBySnapshotIdStartingWith(String prefix);

    /** 按 prefix 计数（避免全量水合仅取 size）。 */
    long countBySnapshotIdStartingWith(String prefix);

    @Query("SELECT s FROM DataSnapshot s ORDER BY s.createdAt DESC")
    List<DataSnapshot> findLatest(Pageable pageable);

    @Query("SELECT COUNT(s) FROM DataSnapshot s")
    long count();
}
