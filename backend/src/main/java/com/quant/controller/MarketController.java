package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.MarketService;
import com.quant.service.RealtimeQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;
    private final RealtimeQuoteService realtimeQuoteService;

    /** 获取股票列表 */
    @GetMapping("/stock/list")
    public ApiResponse<Map<String, Object>> getStockList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String exchange,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> data = marketService.getStockList(keyword, exchange, page, size);
        return ApiResponse.success(data);
    }

    /** 获取股票详情(基础信息+最新行情) */
    @GetMapping("/stock/detail")
    public ApiResponse<Map<String, Object>> getStockDetail(
            @RequestParam String code,
            @RequestParam(required = false) String exchange) {
        Map<String, Object> data = marketService.getStockDetail(code, exchange);
        return ApiResponse.success(data);
    }

    /** 获取实时行情（在线接口） */
    @GetMapping("/realtime")
    public ApiResponse<Map<String, Object>> getRealtime(
            @RequestParam String code,
            @RequestParam(required = false) String exchange) {
        Map<String, Object> data = realtimeQuoteService.fetchRealtimeQuote(code, exchange);
        return ApiResponse.success(data);
    }

    /** 获取K线数据 */
    @GetMapping("/kline")
    public ApiResponse<List<Map<String, Object>>> getKline(
            @RequestParam String symbol,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "500") int limit) {
        List<Map<String, Object>> data = marketService.getKlineData(symbol, exchange, startDate, endDate, limit);
        return ApiResponse.success(data);
    }
}
