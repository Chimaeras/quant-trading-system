package com.quant.repository;

import com.quant.entity.ResearchTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResearchTaskRepository extends JpaRepository<ResearchTask, Long> {

    Optional<ResearchTask> findByTaskId(String taskId);

    Optional<ResearchTask> findByRequestId(String requestId);

    long countByStatus(String status);

    List<ResearchTask> findByStatus(String status);

    /** 工作台列表：最近更新的任务优先（产品工作台/页面重进恢复，V2-MP-019）。 */
    List<ResearchTask> findTop20ByOrderByUpdatedAtDesc();

    /** 同 requestId 重复创建时查重（幂等，V2-MP-008）。 */
    Optional<ResearchTask> findFirstByRequestIdOrderByIdAsc(String requestId);

    /**
     * CAS 状态迁移：仅当当前状态 = {@code from} 时迁移到 {@code to}，并自增 version。
     * 返回受影响行数（0 表示并发冲突或状态已变，调用方必须据此判断，不得覆盖）。
     */
    @Modifying
    @Query("update ResearchTask t set t.status = :to, t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status = :from")
    int casTransition(@Param("taskId") String taskId,
                      @Param("from") String from,
                      @Param("to") String to);

    /**
     * 成功发布（仅当仍 RUNNING 且未被请求取消）：写入结果并置 published=true。
     * 返回受影响行数；0 表示任务已被取消或已迁移，禁止回写 COMPLETED。
     */
    @Modifying
    @Query("update ResearchTask t set t.status = :to, t.progress = 100, "
            + "t.resultJson = :resultJson, t.errorMessage = null, t.errorCode = null, "
            + "t.published = true, t.heartbeatAt = current_timestamp, "
            + "t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status = :running and t.cancelRequested = false")
    int publishOnSuccess(@Param("taskId") String taskId,
                         @Param("resultJson") String resultJson,
                         @Param("to") String to,
                         @Param("running") String running);

    /**
     * 业务无产物（status=no_matches/no_calendar/INSUFFICIENT_DATA 等）：任务 COMPLETED
     * 但 published 保持 false，并记录 errorCode，避免占位结果被当作正式产物。
     * 同样受 cancelRequested 保护。
     */
    @Modifying
    @Query("update ResearchTask t set t.status = :to, t.progress = 100, "
            + "t.resultJson = :resultJson, t.errorMessage = null, t.errorCode = :errorCode, "
            + "t.published = false, t.heartbeatAt = current_timestamp, "
            + "t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status = :running and t.cancelRequested = false")
    int completeWithoutPublish(@Param("taskId") String taskId,
                               @Param("resultJson") String resultJson,
                               @Param("to") String to,
                               @Param("running") String running,
                               @Param("errorCode") String errorCode);

    /** 失败：仅当仍 RUNNING 时写入 FAILED + 错误信息；已取消/已迁移则不动。 */
    @Modifying
    @Query("update ResearchTask t set t.status = :to, t.progress = :progress, "
            + "t.errorMessage = :errorMessage, t.errorCode = :errorCode, "
            + "t.published = false, t.heartbeatAt = current_timestamp, "
            + "t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status = :running")
    int failIfRunning(@Param("taskId") String taskId,
                      @Param("to") String to,
                      @Param("running") String running,
                      @Param("errorMessage") String errorMessage,
                      @Param("errorCode") String errorCode,
                      @Param("progress") int progress);

    /** 请求取消：置 cancelRequested=true（协作式）。worker 发布前必须看到该标记。 */
    @Modifying
    @Query("update ResearchTask t set t.cancelRequested = true, "
            + "t.version = t.version + 1 where t.taskId = :taskId and t.status = :running")
    int requestCancel(@Param("taskId") String taskId, @Param("running") String running);

    /**
     * 协作式取消生效：仅当 cancelRequested 已置位时 RUNNING→CANCELLED。
     * 返回 1 表示已转取消；0 表示未请求取消（worker 继续发布）。与发布查询同一 CAS 语义。
     */
    @Modifying
    @Query("update ResearchTask t set t.status = :to, t.published = false, "
            + "t.errorMessage = :reason, t.heartbeatAt = current_timestamp, "
            + "t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status = :from and t.cancelRequested = true")
    int cancelIfRequested(@Param("taskId") String taskId,
                          @Param("to") String to,
                          @Param("from") String from,
                          @Param("reason") String reason);

    /** 恢复（FAILED/CANCELLED/RECOVERY_REQUIRED → PENDING），并保留原任务记录。 */
    @Modifying
    @Query("update ResearchTask t set t.status = :to, t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status in (:from1, :from2, :from3)")
    int resumeIfRecoverable(@Param("taskId") String taskId,
                            @Param("to") String to,
                            @Param("from1") String from1,
                            @Param("from2") String from2,
                            @Param("from3") String from3);

    /** 恢复后清理：清空产物/错误/取消标记，重新排队，进度归零。 */
    @Modifying
    @Query("update ResearchTask t set t.progress = 0, t.resultJson = null, "
            + "t.errorMessage = null, t.errorCode = null, t.published = false, "
            + "t.cancelRequested = false, t.heartbeatAt = current_timestamp, "
            + "t.version = t.version + 1 where t.taskId = :taskId")
    int resetForResume(@Param("taskId") String taskId);

    /** worker 心跳：RUNNING 中定期更新时间戳（长任务保活）。 */
    @Modifying
    @Query("update ResearchTask t set t.heartbeatAt = current_timestamp, "
            + "t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status = :running")
    int heartbeat(@Param("taskId") String taskId, @Param("running") String running);

    /** 分区进度上报（产品工作台，V2-MP-019）：仅 RUNNING 时生效，取消/迁移后忽略。 */
    @Modifying
    @Query("update ResearchTask t set t.progress = :progress, t.checkpoint = :checkpoint, "
            + "t.heartbeatAt = current_timestamp, t.version = t.version + 1 "
            + "where t.taskId = :taskId and t.status = :running")
    int reportProgress(@Param("taskId") String taskId,
                       @Param("progress") int progress,
                       @Param("checkpoint") String checkpoint,
                       @Param("running") String running);

    /** 启动恢复：把遗留 RUNNING 标记为 RECOVERY_REQUIRED，等待人工决策；PENDING 由监听器重新排队。 */
    @Modifying
    @Query("update ResearchTask t set t.status = 'RECOVERY_REQUIRED', "
            + "t.errorMessage = '应用重启中断，需人工确认恢复', "
            + "t.version = t.version + 1 where t.status = 'RUNNING'")
    int markInterruptedTasksForRecovery();
}
