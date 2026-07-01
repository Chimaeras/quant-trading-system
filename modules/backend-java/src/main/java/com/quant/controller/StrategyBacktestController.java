package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.StrategyBacktestRequest;
import com.quant.dto.StrategyBacktestResponse;
import com.quant.service.StrategyBacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/backtest/strategy")
@RequiredArgsConstructor
public class StrategyBacktestController {

    private final StrategyBacktestService strategyBacktestService;

    @PostMapping("/run")
    public ApiResponse<StrategyBacktestResponse> run(@RequestBody(required = false) StrategyBacktestRequest request) {
        StrategyBacktestRequest req = request != null ? request : new StrategyBacktestRequest();
        return ApiResponse.success(strategyBacktestService.run(req));
    }

    @GetMapping("/strategies")
    public ApiResponse<List<String>> strategies() {
        return ApiResponse.success(strategyBacktestService.getStrategies());
    }
}
