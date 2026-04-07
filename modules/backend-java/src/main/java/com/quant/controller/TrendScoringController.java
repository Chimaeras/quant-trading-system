package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.StockTrendScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/scoring/trend")
@RequiredArgsConstructor
public class TrendScoringController {

    private final StockTrendScoringService stockTrendScoringService;

    /** 按文档规则批量计算趋势打分并落库（默认交易日为库内最新日） */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(stockTrendScoringService.run(request != null ? request : Map.of()));
    }

    /** 查询已保存的打分列表（按总分降序）；未传 tradeDate 时使用库中最新一条交易日 */
    @GetMapping("/scores")
    public ApiResponse<Map<String, Object>> scores(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(stockTrendScoringService.listScores(tradeDate, page, size));
    }
}
