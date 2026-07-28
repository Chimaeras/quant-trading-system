package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.StrategyCompareService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 策略对比工作台。
 */
@RestController
@RequestMapping("/api/compare")
@RequiredArgsConstructor
public class StrategyCompareController {

    private final StrategyCompareService strategyCompareService;

    @GetMapping("/strategies")
    public ApiResponse<Map<String, Object>> compare(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.success(strategyCompareService.compare(tradeDate, topN));
    }
}
