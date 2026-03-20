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
        Map<String, Object> result = backtestService.runBacktest(userDetails.getUsername(), request);
        return ApiResponse.success(result);
    }
}
