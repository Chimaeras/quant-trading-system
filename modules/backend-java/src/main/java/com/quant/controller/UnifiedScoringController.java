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
    private final com.quant.service.scoring.factor.UnifiedFactorScoringService unifiedFactorScoringService;
    private final com.quant.service.scoring.factor.UnifiedFactorComparisonService unifiedFactorComparisonService;
    private final com.quant.service.scoring.factor.StrategyCatalogService strategyCatalogService;
    private final com.quant.service.scoring.factor.UnifiedScoreReadService unifiedScoreReadService;

    /**
     * 一次打分，5 个策略入口全部就绪（重构目标的落点）。
     *
     * <p>行情装载与 L1 特征计算<b>只做一次</b>，5 个策略共享同一份特征向量；
     * 各策略各做一次横截面加权与排名（权重不同，排序必须独立）。
     * 结果已投影为既有 {@code selector_result_item} 字段名，前端 5 个 tab 可直接消费。
     *
     * <p><b>只读，不落库</b>：落库需先确定权威运行语义（见方案文档 §8.8.6）。
     */
    @PostMapping("/engine/all-strategies")
    public ApiResponse<Map<String, Object>> engineAllStrategies(
            @RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(unifiedScoreReadService.runAllStrategies(
                request != null ? request : Map.of()));
    }

    /**
     * 策略目录（批次 D 的单一真相）。前端据此渲染 5 个策略入口，不再自己硬编码映射表。
     *
     * <p>只读、不查库，可在任何环境安全调用。
     */
    @GetMapping("/strategies")
    public ApiResponse<java.util.List<Map<String, Object>>> strategies() {
        return ApiResponse.success(strategyCatalogService.listStrategies());
    }

    /** 单个策略的元数据（含逐因子权重与缺失数据策略）。 */
    @GetMapping("/strategies/{legacyStrategyType}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> strategy(@PathVariable String legacyStrategyType) {
        Map<String, Object> data = strategyCatalogService.describeOne(legacyStrategyType);
        if (data == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "未知策略: " + legacyStrategyType));
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 统一因子引擎直接入口（批次 C 影子模式，只读不落库）。
     *
     * <p>与 {@code /run} 的区别：{@code /run} 按 {@code strategy} 分派，5 个旧策略走各自实现；
     * 本入口专门走新引擎，用 {@code unifiedStrategy} 指定风格（缺省 CLASSIC_SELECTOR）。
     * 单独开入口而非复用 {@code /run?strategy=UNIFIED}，是为了让"走新引擎"这件事在
     * 调用侧显式可见，避免误以为在用旧策略。
     */
    @PostMapping("/engine/run")
    public ApiResponse<Map<String, Object>> engineRun(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(unifiedFactorScoringService.run(request != null ? request : Map.of()));
    }

    /**
     * 旧策略 vs 统一引擎的同日对账（批次 C）。
     *
     * <p>只描述差异、不判定优劣：排序相关性不构成质量证据，优劣要用 forward return 验证。
     */
    @PostMapping("/engine/compare")
    public ApiResponse<Map<String, Object>> engineCompare(
            @RequestParam(defaultValue = "CLASSIC_SELECTOR") String legacyStrategy,
            @RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(unifiedFactorComparisonService.compare(
                legacyStrategy, request != null ? request : Map.of()));
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(unifiedScoringService.run(request != null ? request : Map.of()));
    }

    @PostMapping("/tasks/run")
    public ApiResponse<Map<String, Object>> runTask(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(unifiedScoringService.createTask(request != null ? request : Map.of()));
    }

    @GetMapping("/tasks/recent")
    public ApiResponse<java.util.List<Map<String, Object>>> recentTasks() {
        return ApiResponse.success(unifiedScoringService.recentTasks());
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
