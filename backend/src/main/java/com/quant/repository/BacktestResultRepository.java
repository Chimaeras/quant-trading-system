package com.quant.repository;

import com.quant.entity.BacktestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BacktestResultRepository extends JpaRepository<BacktestResultEntity, Long> {
    Optional<BacktestResultEntity> findByResultId(String resultId);
}
