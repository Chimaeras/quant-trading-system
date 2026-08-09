package com.quant.repository;

import com.quant.entity.SimulationSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 历史演练会话（V2-MP-021）。 */
public interface SimulationSessionRepository extends JpaRepository<SimulationSession, Long> {

    /** 账户下全部会话（新建在前）。 */
    List<SimulationSession> findByAccountIdOrderByIdDesc(Long accountId);

    Optional<SimulationSession> findByIdAndAccountId(Long id, Long accountId);

    List<SimulationSession> findByUserId(Long userId);
}
