package com.quant.repository;

import com.quant.entity.DataUpdateTaskItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataUpdateTaskItemRepository extends JpaRepository<DataUpdateTaskItem, Long> {
    List<DataUpdateTaskItem> findByTaskIdOrderByIdAsc(String taskId);
    Page<DataUpdateTaskItem> findByTaskId(String taskId, Pageable pageable);
    List<DataUpdateTaskItem> findByTaskIdAndStatus(String taskId, String status);
}
