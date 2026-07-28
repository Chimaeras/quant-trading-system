package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.BacktestStabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 回测分段稳定性报告。
 */
@RestController
@RequestMapping("/api/backtest/stability")
@RequiredArgsConstructor
public class BacktestStabilityController {

    private final BacktestStabilityService backtestStabilityService;

    @GetMapping
    public ApiResponse<Map<String, Object>> analyze(
            @RequestParam String strategy,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer topN,
            @RequestParam(required = false) Integer holdingDay) {
        return ApiResponse.success(
                backtestStabilityService.analyze(strategy, startDate, endDate, topN, holdingDay));
    }
}
