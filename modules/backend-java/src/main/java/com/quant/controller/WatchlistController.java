package com.quant.controller;

import com.quant.dto.ApiResponse;
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

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(watchlistService.list(userId != null ? userId : 1L));
    }

    @PostMapping("/add")
    public ApiResponse<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") != null ? Long.valueOf(String.valueOf(body.get("userId"))) : 1L;
        String stockCode = String.valueOf(body.getOrDefault("stockCode", ""));
        String exchangeType = String.valueOf(body.getOrDefault("exchangeType", ""));
        String note = body.get("note") != null ? String.valueOf(body.get("note")) : null;
        return ApiResponse.success(watchlistService.add(userId, stockCode, exchangeType, note));
    }

    @PostMapping("/remove")
    public ApiResponse<Map<String, Object>> remove(@RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") != null ? Long.valueOf(String.valueOf(body.get("userId"))) : 1L;
        String stockCode = String.valueOf(body.getOrDefault("stockCode", ""));
        String exchangeType = String.valueOf(body.getOrDefault("exchangeType", ""));
        return ApiResponse.success(watchlistService.remove(userId, stockCode, exchangeType));
    }

    @GetMapping("/alerts")
    public ApiResponse<Map<String, Object>> alerts(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Double pctThreshold,
            @RequestParam(required = false) Double volumeRatioThreshold,
            @RequestParam(required = false) Integer lookbackDays) {
        return ApiResponse.success(watchlistAlertService.scan(
                userId != null ? userId : 1L, pctThreshold, volumeRatioThreshold, lookbackDays));
    }
}
