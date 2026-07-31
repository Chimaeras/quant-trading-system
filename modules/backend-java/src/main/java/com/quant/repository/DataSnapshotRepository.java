package com.quant.repository;

import com.quant.entity.DataSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DataSnapshotRepository extends JpaRepository<DataSnapshot, Long> {

    List<DataSnapshot> findBySnapshotId(String snapshotId);

    List<DataSnapshot> findBySnapshotIdStartingWith(String prefix);

    @Query("SELECT s FROM DataSnapshot s ORDER BY s.createdAt DESC")
    List<DataSnapshot> findLatest(int limit);

    @Query("SELECT COUNT(s) FROM DataSnapshot s")
    long count();
}
