package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.scoring.UnifiedScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 统一打分入口：通过 strategy 选择不同打分策略。
 */
@RestController
@RequestMapping("/api/scoring/unified")
@RequiredArgsConstructor
public class UnifiedScoringController {

    private final UnifiedScoringService unifiedScoringService;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(unifiedScoringService.run(request != null ? request : Map.of()));
    }

    @PostMapping("/tasks/run")
    public ApiResponse<Map<String, Object>> runTask(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(unifiedScoringService.createTask(request != null ? request : Map.of()));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> task(@PathVariable String taskId) {
        Map<String, Object> data = unifiedScoringService.getTask(taskId);
        if (data == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "任务不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        return ApiResponse.success(unifiedScoringService.cancelTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/prioritize")
    public ApiResponse<Map<String, Object>> prioritizeTask(@PathVariable String taskId) {
        return ApiResponse.success(unifiedScoringService.prioritizeTask(taskId));
    }

    @PostMapping("/tasks/clear-pending")
    public ApiResponse<Map<String, Object>> clearPendingTasks() {
        return ApiResponse.success(unifiedScoringService.clearPendingTasks());
    }

    @GetMapping("/result/latest")
    public ApiResponse<Map<String, Object>> latest(
            @RequestParam String strategy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(unifiedScoringService.getLatest(
                strategy,
                tradeDate != null ? tradeDate.toString() : null,
                page,
                size
        ));
    }

    @GetMapping("/result/{resultId}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable String resultId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(unifiedScoringService.getByResultId(resultId, page, size));
    }
}
