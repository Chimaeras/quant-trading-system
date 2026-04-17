package com.quant.controller;

import com.quant.service.scoring.UnifiedScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
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

    private static Map<String, Object> ok(Map<String, Object> data) {
        return Map.of(
                "code", 200,
                "message", "success",
                "data", data != null ? data : Map.of()
        );
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ok(unifiedScoringService.run(request != null ? request : Map.of())));
    }

    @PostMapping("/tasks/run")
    public ResponseEntity<Map<String, Object>> runTask(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ok(unifiedScoringService.createTask(request != null ? request : Map.of())));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> task(@PathVariable String taskId) {
        Map<String, Object> data = unifiedScoringService.getTask(taskId);
        if (data == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code", 404,
                    "message", "任务不存在",
                    "data", Map.of()
            ));
        }
        return ResponseEntity.ok(ok(data));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        return ResponseEntity.ok(ok(unifiedScoringService.cancelTask(taskId)));
    }

    @PostMapping("/tasks/{taskId}/prioritize")
    public ResponseEntity<Map<String, Object>> prioritizeTask(@PathVariable String taskId) {
        return ResponseEntity.ok(ok(unifiedScoringService.prioritizeTask(taskId)));
    }

    @PostMapping("/tasks/clear-pending")
    public ResponseEntity<Map<String, Object>> clearPendingTasks() {
        return ResponseEntity.ok(ok(unifiedScoringService.clearPendingTasks()));
    }

    @GetMapping("/result/latest")
    public ResponseEntity<Map<String, Object>> latest(
            @RequestParam String strategy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(ok(unifiedScoringService.getLatest(
                strategy,
                tradeDate != null ? tradeDate.toString() : null,
                page,
                size
        )));
    }

    @GetMapping("/result/{resultId}")
    public ResponseEntity<Map<String, Object>> detail(
            @PathVariable String resultId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(ok(unifiedScoringService.getByResultId(resultId, page, size)));
    }
}
