package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.BacktestTurnoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 回测换手率分析。
 */
@RestController
@RequestMapping("/api/backtest/turnover")
@RequiredArgsConstructor
public class BacktestTurnoverController {

    private final BacktestTurnoverService backtestTurnoverService;

    @GetMapping
    public ApiResponse<Map<String, Object>> analyze(
            @RequestParam(required = false) String strategy,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer topN) {
        return ApiResponse.success(backtestTurnoverService.analyze(strategy, startDate, endDate, topN));
    }
}
