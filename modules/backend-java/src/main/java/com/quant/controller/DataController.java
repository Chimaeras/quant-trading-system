package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.DataService;
import com.quant.service.HistoricalDataService;
import com.quant.service.HistoricalDataUpdateService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final DataService dataService;
    private final HistoricalDataService historicalDataService;
    private final HistoricalDataUpdateService historicalDataUpdateService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(dataService.getOverview());
    }

    /** 历史行情立即更新：按股票+日期范围拉取，仅补充缺失的交易日 */
    @PostMapping("/history/update")
    public ApiResponse<Map<String, Object>> updateHistory(
            @RequestParam String stockCode,
            @RequestParam(required = false) String exchange,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> result = historicalDataService.updateHistoryInRange(stockCode, exchange, startDate, endDate);
        return ApiResponse.success(result);
    }

    /** 任务化更新：支持 single/all/list */
    @PostMapping("/update/run")
    public ApiResponse<Map<String, Object>> runUpdateTask(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> data = historicalDataUpdateService.createTask(request != null ? request : Map.of());
        return ApiResponse.success(data);
    }

    @GetMapping("/update/tasks")
    public ApiResponse<Map<String, Object>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(historicalDataUpdateService.listTasks(page, size));
    }

    @GetMapping("/update/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(@PathVariable String taskId) {
        Map<String, Object> data = historicalDataUpdateService.getTaskDetail(taskId);
        if (data == null) return ApiResponse.error(404, "任务不存在");
        return ApiResponse.success(data);
    }

    @PostMapping("/update/tasks/{taskId}/retry")
    public ApiResponse<Map<String, Object>> retryTask(@PathVariable String taskId) {
        Map<String, Object> data = historicalDataUpdateService.retryFailed(taskId);
        if (data == null) return ApiResponse.error(404, "任务不存在");
        return ApiResponse.success(data);
    }
}
