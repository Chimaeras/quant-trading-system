package com.quant.repository;

import com.quant.entity.PortfolioBacktestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PortfolioBacktestRunRepository extends JpaRepository<PortfolioBacktestRun, Long> {

    Optional<PortfolioBacktestRun> findByRunId(String runId);

    /** RUNNING 期间进度/updated_at 心跳（status 条件防覆盖终态）。 */
    @Modifying
    @Transactional
    @Query("UPDATE PortfolioBacktestRun r SET r.progress = :progress, r.updatedAt = :ts "
            + "WHERE r.runId = :runId AND r.status = :status")
    int updateProgressIf(
            @Param("runId") String runId,
            @Param("status") String status,
            @Param("progress") int progress,
            @Param("ts") LocalDateTime ts);

    /** 失败终态写入（含截断 error message）。 */
    @Modifying
    @Transactional
    @Query("UPDATE PortfolioBacktestRun r SET r.status = :status, r.errorMessage = :error, "
            + "r.updatedAt = :ts WHERE r.runId = :runId")
    int markFailed(
            @Param("runId") String runId,
            @Param("status") String status,
            @Param("error") String error,
            @Param("ts") LocalDateTime ts);

    /**
     * 启动恢复：将上次崩溃遗留的 RUNNING 行批量标记为 FAILED。
     * 组合回测无 PENDING 中间态（submitRun 直接落 RUNNING），故仅处理 RUNNING 孤儿。
     */
    @Modifying
    @Transactional
    @Query("UPDATE PortfolioBacktestRun r SET r.status = 'FAILED', "
            + "r.errorMessage = :error, r.updatedAt = :ts WHERE r.status = 'RUNNING'")
    int markOrphanedRunsFailed(@Param("error") String error, @Param("ts") LocalDateTime ts);
}
