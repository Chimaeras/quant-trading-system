package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.WalkForwardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 样本外 / walk-forward 简易框架。
 */
@RestController
@RequestMapping("/api/backtest/walk-forward")
@RequiredArgsConstructor
public class WalkForwardController {

    private final WalkForwardService walkForwardService;

    @GetMapping
    public ApiResponse<Map<String, Object>> run(
            @RequestParam String strategy,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer windows,
            @RequestParam(required = false) Integer topN,
            @RequestParam(required = false) Integer holdingDay) {
        return ApiResponse.success(
                walkForwardService.run(strategy, startDate, endDate, windows, topN, holdingDay));
    }
}
