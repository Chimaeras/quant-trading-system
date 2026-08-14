package com.quant.repository;

import com.quant.entity.DataUpdateTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DataUpdateTaskRepository extends JpaRepository<DataUpdateTask, Long> {
    Optional<DataUpdateTask> findByTaskId(String taskId);

    /**
     * 启动恢复：将上次崩溃遗留的非终态任务（PENDING/RUNNING/STOPPING）批量标记为 FAILED，
     * 避免孤儿行永远卡在非终态（无 worker 附着、can_stop 仍为 true 但 stopTask 找不到 future）。
     */
    @Modifying
    @Query("UPDATE DataUpdateTask t SET t.status = 'FAILED' " +
            "WHERE t.status NOT IN ('SUCCESS','PARTIAL','FAILED','STOPPED')")
    int markOrphanedTasksFailed();

    /** 查询当前仍处于给定状态的任务（用于启动恢复日志）。 */
    List<DataUpdateTask> findByStatus(String status);
}
