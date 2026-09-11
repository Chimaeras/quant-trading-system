package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.BodyValues;
import com.quant.security.UserIdResolver;
import com.quant.service.WatchlistAlertService;
import com.quant.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 自选股池管理。
 */
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;
    private final WatchlistAlertService watchlistAlertService;
    private final UserIdResolver userIdResolver;

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(watchlistService.list(userIdResolver.resolve(userId)));
    }

    @PostMapping("/add")
    public ApiResponse<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        Long userId = BodyValues.parseOptionalLong(body.get("userId"), "userId");
        String stockCode = String.valueOf(body.getOrDefault("stockCode", ""));
        String exchangeType = String.valueOf(body.getOrDefault("exchangeType", ""));
        String note = body.get("note") != null ? String.valueOf(body.get("note")) : null;
        return ApiResponse.success(watchlistService.add(userIdResolver.resolve(userId), stockCode, exchangeType, note));
    }

    @PostMapping("/remove")
    public ApiResponse<Map<String, Object>> remove(@RequestBody Map<String, Object> body) {
        Long userId = BodyValues.parseOptionalLong(body.get("userId"), "userId");
        String stockCode = String.valueOf(body.getOrDefault("stockCode", ""));
        String exchangeType = String.valueOf(body.getOrDefault("exchangeType", ""));
        return ApiResponse.success(watchlistService.remove(userIdResolver.resolve(userId), stockCode, exchangeType));
    }

    @PostMapping("/batch-add")
    public ApiResponse<Map<String, Object>> batchAdd(@RequestBody Map<String, Object> body) {
        Long userId = BodyValues.parseOptionalLong(body.get("userId"), "userId");
        Object text = body.get("text");
        List<String[]> pairs = new java.util.ArrayList<>();
        if (text instanceof String s) {
            for (String line : s.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                // 支持「代码,交易所」「代码 交易所」「代码」（逗号/空格/制表符分隔）
                String[] parts = t.split("[,\\s]+");
                pairs.add(parts);
            }
        }
        return ApiResponse.success(watchlistService.batchAdd(userIdResolver.resolve(userId), pairs));
    }

    @GetMapping("/alerts")
    public ApiResponse<Map<String, Object>> alerts(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Double pctThreshold,
            @RequestParam(required = false) Double volumeRatioThreshold,
            @RequestParam(required = false) Integer lookbackDays) {
        return ApiResponse.success(watchlistAlertService.scan(
                userIdResolver.resolve(userId), pctThreshold, volumeRatioThreshold, lookbackDays));
    }

    @GetMapping("/signal-coverage")
    public ApiResponse<Map<String, Object>> signalCoverage(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(watchlistService.signalCoverage(userIdResolver.resolve(userId)));
    }
}
