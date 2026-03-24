package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.BacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> runBacktest(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        String username = (userDetails != null && userDetails.getUsername() != null) ? userDetails.getUsername() : "admin";
        Map<String, Object> result = backtestService.runBacktest(username, request);
        return ApiResponse.success(result);
    }

    @GetMapping("/results")
    public ApiResponse<Map<String, Object>> listResults(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(backtestService.listResults(page, size));
    }

    @GetMapping("/results/{resultId}")
    public ApiResponse<Map<String, Object>> getResult(@PathVariable String resultId) {
        Map<String, Object> detail = backtestService.getResultDetail(resultId);
        if (detail == null) return ApiResponse.error(404, "回测结果不存在");
        return ApiResponse.success(detail);
    }
}
