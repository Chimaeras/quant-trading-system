package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.StockAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class StockAnalysisController {
    private final StockAnalysisService stockAnalysisService;

    @PostMapping("/stock")
    public ApiResponse<Map<String, Object>> analyzeStock(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(stockAnalysisService.analyze(request != null ? request : Map.of()));
    }
}
