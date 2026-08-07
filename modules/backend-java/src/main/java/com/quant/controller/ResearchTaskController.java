package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.research.ResearchTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 研究异步任务 API（MP-024）。
 */
@RestController
@RequestMapping("/api/research/tasks")
@RequiredArgsConstructor
public class ResearchTaskController {

    private final ResearchTaskService researchTaskService;

    @PostMapping
    public ApiResponse<Map<String, Object>> createTask(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(researchTaskService.createTask(request));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(@PathVariable String taskId) {
        Map<String, Object> task = researchTaskService.getTask(taskId);
        if (task == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        return ApiResponse.success(task);
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
