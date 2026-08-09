package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.exception.BusinessException;
import com.quant.research.ResearchConvention;
import com.quant.service.ConcurrencyGuard;
import com.quant.service.ConflictRetry;
import com.quant.service.PredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 每日双轨预测快照 API（MP-011）。
 */
@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictionService;
    private final ConflictRetry conflictRetry;
    private final ConcurrencyGuard concurrencyGuard;

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> daily(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "2") int horizon) {
        return ApiResponse.success(predictionService.getDaily(date, horizon));
    }

    /** V2-MP-020：最近 prediction run 列表（组合回测闭环信号源选择器）。 */
    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(predictionService.listRuns(limit));
    }

    /** V2-MP-028：按 runId 拉取 run 详情（含 items）；生成发布后前端据此展示，不再把空 items 当成功。 */
    @GetMapping("/runs/{runId}")
    public ApiResponse<Map<String, Object>> runDetail(@PathVariable("runId") String runId) {
        return ApiResponse.success(predictionService.getRunDetail(runId));
    }

    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "2") int horizon,
            @RequestParam(required = false) String requestId) {
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)) {
            throw new BusinessException("horizon 仅支持 2 或 3");
        }
        // V2-MP-030: 并发上限 429 + requestId 幂等重放 + MAX+1 竞态有界重试（既有唯一约束为 CAS 门）
        String guardKey = "prediction-generate";
        concurrencyGuard.tryEnter(guardKey, 3);
        try {
            Map<String, Object> result = conflictRetry.retryOnUniqueConflict(
                    () -> predictionService.generate(date, horizon, requestId), "预测发布");
            return ApiResponse.success(result);
        } finally {
            concurrencyGuard.exit(guardKey);
        }
    }
}
