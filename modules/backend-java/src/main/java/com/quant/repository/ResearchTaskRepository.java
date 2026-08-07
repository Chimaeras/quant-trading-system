package com.quant.repository;

import com.quant.entity.ResearchTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResearchTaskRepository extends JpaRepository<ResearchTask, Long> {

    Optional<ResearchTask> findByTaskId(String taskId);

    long countByStatus(String status);
}
