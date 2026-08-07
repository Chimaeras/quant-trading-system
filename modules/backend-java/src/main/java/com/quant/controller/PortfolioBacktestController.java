package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.PortfolioBacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 事件驱动组合回测 API（MP-015）。
 */
@RestController
@RequestMapping("/api/backtest/portfolio")
@RequiredArgsConstructor
public class PortfolioBacktestController {

    private final PortfolioBacktestService portfolioBacktestService;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(portfolioBacktestService.run(body != null ? body : Map.of()));
    }

    @GetMapping("/{runId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String runId) {
        return ApiResponse.success(portfolioBacktestService.getRun(runId));
    }
}
