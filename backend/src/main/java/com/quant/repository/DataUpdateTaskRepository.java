package com.quant.repository;

import com.quant.entity.DataUpdateTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DataUpdateTaskRepository extends JpaRepository<DataUpdateTask, Long> {
    Optional<DataUpdateTask> findByTaskId(String taskId);
}
