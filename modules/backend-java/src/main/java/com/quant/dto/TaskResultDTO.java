package com.quant.dto;

import java.time.LocalDateTime;

/**
 * 任务执行结果DTO
 * 统一的异步任务状态返回格式
 */
public class TaskResultDTO {

    private String taskId;
    private String strategy;
    private String status;  // PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
    private int progressPct;
    private String message;
    private String errorMessage;
    private String resultId;
    private int totalItems;
    private int returnedItems;
    private long pendingAhead;
    private long runningCount;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;

    public TaskResultDTO() {}

    public static TaskResultDTO pending(String taskId, String strategy, String message) {
        TaskResultDTO dto = new TaskResultDTO();
        dto.taskId = taskId;
        dto.strategy = strategy;
        dto.status = "PENDING";
        dto.progressPct = 0;
        dto.message = message;
        return dto;
    }

    public static TaskResultDTO running(String taskId, String strategy, int progressPct, String message) {
        TaskResultDTO dto = new TaskResultDTO();
        dto.taskId = taskId;
        dto.strategy = strategy;
        dto.status = "RUNNING";
        dto.progressPct = progressPct;
        dto.message = message;
        return dto;
    }

    public static TaskResultDTO success(String taskId, String strategy, String resultId, int totalItems) {
        TaskResultDTO dto = new TaskResultDTO();
        dto.taskId = taskId;
        dto.strategy = strategy;
        dto.status = "SUCCESS";
        dto.progressPct = 100;
        dto.resultId = resultId;
        dto.totalItems = totalItems;
        dto.returnedItems = totalItems;
        dto.message = "任务执行完成";
        return dto;
    }

    public static TaskResultDTO failed(String taskId, String strategy, String errorMessage) {
        TaskResultDTO dto = new TaskResultDTO();
        dto.taskId = taskId;
        dto.strategy = strategy;
        dto.status = "FAILED";
        dto.errorMessage = errorMessage;
        dto.message = "任务执行失败";
        return dto;
    }

    public static TaskResultDTO cancelled(String taskId, String strategy, String reason) {
        TaskResultDTO dto = new TaskResultDTO();
        dto.taskId = taskId;
        dto.strategy = strategy;
        dto.status = "CANCELLED";
        dto.message = reason != null ? reason : "任务已取消";
        return dto;
    }

    // ==================== Getters and Setters ====================

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgressPct() { return progressPct; }
    public void setProgressPct(int progressPct) { this.progressPct = progressPct; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getResultId() { return resultId; }
    public void setResultId(String resultId) { this.resultId = resultId; }
    public int getTotalItems() { return totalItems; }
    public void setTotalItems(int totalItems) { this.totalItems = totalItems; }
    public int getReturnedItems() { return returnedItems; }
    public void setReturnedItems(int returnedItems) { this.returnedItems = returnedItems; }
    public long getPendingAhead() { return pendingAhead; }
    public void setPendingAhead(long pendingAhead) { this.pendingAhead = pendingAhead; }
    public long getRunningCount() { return runningCount; }
    public void setRunningCount(long runningCount) { this.runningCount = runningCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
