package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.DailyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 选股日报（V2-MP-012）：prediction snapshot 不可变投影。
 *
 * <p>daily 显式选 (tradeDate, horizon, track, publishVersion)；无 PUBLISHED run 时报 abstain，
 * 绝不回退昨日。versions 提供版本选择器；realize 到期后追加 realization（幂等，不覆盖）。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class DailyReportController {

    private final DailyReportService dailyReportService;

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> daily(
            @RequestParam(value = "tradeDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(value = "horizon", defaultValue = "3") int horizon,
            @RequestParam(value = "track", defaultValue = "RULE_SCORE") String track,
            @RequestParam(value = "publishVersion", required = false) Integer publishVersion) {
        return ApiResponse.success(dailyReportService.buildDailyReport(tradeDate, horizon, track, publishVersion));
    }

    /** 某日某轨道全部可发布版本（版本选择器；tradeDate 省略返回空列表）。 */
    @GetMapping("/daily/versions")
    public ApiResponse<Map<String, Object>> versions(
            @RequestParam(value = "tradeDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(value = "horizon", defaultValue = "3") int horizon,
            @RequestParam(value = "track", defaultValue = "RULE_SCORE") String track) {
        return ApiResponse.success(dailyReportService.listVersions(tradeDate, horizon, track));
    }

    /** 到期后追加 realization（幂等：已落库返回既有结果，不覆盖）。 */
    @PostMapping("/daily/realize")
    public ApiResponse<Map<String, Object>> realize(
            @RequestParam(value = "tradeDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(value = "horizon", defaultValue = "3") int horizon,
            @RequestParam(value = "track", defaultValue = "RULE_SCORE") String track,
            @RequestParam(value = "publishVersion", required = false) Integer publishVersion) {
        return ApiResponse.success(dailyReportService.realize(tradeDate, horizon, track, publishVersion));
    }

    @GetMapping("/trend")
    public ApiResponse<Map<String, Object>> trend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(dailyReportService.buildTrendReport(startDate, endDate));
    }
}
