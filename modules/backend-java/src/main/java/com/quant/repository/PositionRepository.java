package com.quant.repository;

import com.quant.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PositionRepository extends JpaRepository<Position, Long> {

    List<Position> findByAccountId(Long accountId);

    List<Position> findByAccountIdAndVolumeGreaterThan(Long accountId, Integer volume);
}
