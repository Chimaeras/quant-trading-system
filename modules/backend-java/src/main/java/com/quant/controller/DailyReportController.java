package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.DailyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 选股日报：每日信号摘要。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class DailyReportController {

    private final DailyReportService dailyReportService;

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> daily(
            @RequestParam(value = "tradeDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        return ApiResponse.success(dailyReportService.buildDailyReport(tradeDate));
    }
}
