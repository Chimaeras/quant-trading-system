package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.service.ScoringBacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
