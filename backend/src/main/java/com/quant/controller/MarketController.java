package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @GetMapping("/kline")
    public ApiResponse<List<Map<String, Object>>> getKline(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> data = marketService.getKlineData(symbol, interval, limit);
        return ApiResponse.success(data);
    }
}
