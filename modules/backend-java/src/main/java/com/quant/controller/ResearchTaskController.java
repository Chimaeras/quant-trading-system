package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.research.ResearchTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 研究异步任务 API（MP-024 / V2-MP-008）。
 *
 * <p>HTTP 语义（V2-MP-008 契约）：创建返回 202 Accepted（异步任务已受理未完成）；
 * 任务不存在返回真实 404；非法状态转换返回 409 Conflict。
 */
@RestController
@RequestMapping("/api/research/tasks")
@RequiredArgsConstructor
public class ResearchTaskController {

    private final ResearchTaskService researchTaskService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTask(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> task = researchTaskService.createTask(request);
        HttpStatus status = Boolean.TRUE.equals(task.get("reused")) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(task));
    }

    @GetMapping
    public ApiResponse<java.util.List<Map<String, Object>>> listTasks(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(researchTaskService.listTasks(limit));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(@PathVariable String taskId) {
        // 任务不存在时服务抛 ResourceNotFoundException → 真实 404
        return ApiResponse.success(researchTaskService.getTask(taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        return ApiResponse.success(researchTaskService.cancelTask(taskId));
    }

    @PostMapping("/{taskId}/resume")
    public ApiResponse<Map<String, Object>> resumeTask(@PathVariable String taskId) {
        return ApiResponse.success(researchTaskService.resumeTask(taskId));
    }
}
