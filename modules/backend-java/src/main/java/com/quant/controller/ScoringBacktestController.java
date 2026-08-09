package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.ScoringBacktestRequest;
import com.quant.exception.ResourceNotFoundException;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.service.ScoringBacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/backtest/scoring")
@RequiredArgsConstructor
public class ScoringBacktestController {

    private final ScoringBacktestService scoringBacktestService;

    @PostMapping
    public ApiResponse<ScoringBacktestResponse> run(@RequestBody(required = false) ScoringBacktestRequest request) {
        ScoringBacktestRequest req = request != null ? request : new ScoringBacktestRequest();
        if (req.getStartDate() == null || req.getEndDate() == null) {
            return ApiResponse.error(400, "startDate 和 endDate 为必填项");
        }
        return ApiResponse.success(scoringBacktestService.run(req));
    }

    @GetMapping("/strategies")
    public ApiResponse<List<String>> strategies() {
        return ApiResponse.success(scoringBacktestService.getAvailableStrategies());
    }

    /** 历史回测运行列表（摘要）。 */
    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(scoringBacktestService.listRuns(limit));
    }

    /** 按 resultId 回溯一次历史回测的完整结果。 */
    @GetMapping("/run/{resultId}")
    public ApiResponse<ScoringBacktestResponse> getRun(@PathVariable String resultId) {
        ScoringBacktestResponse resp = scoringBacktestService.getRun(resultId);
        if (resp == null) {
            throw new ResourceNotFoundException("未找到该回测记录: " + resultId);
        }
        return ApiResponse.success(resp);
    }
}
