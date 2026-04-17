package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.scoring.ComprehensiveScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 综合三维度打分API
 *
 * 根据《打分机制》文档实现：
 * - 技术分析维度(30分)：均线排列、RSI指标、MACD指标
 * - 基本面分析维度(40分)：估值水平、盈利能力、财务健康度
 * - 市场情绪分析维度(30分)：新闻舆情、社交媒体讨论热度、机构观点
 */
@RestController
@RequestMapping("/api/scoring/comprehensive")
@RequiredArgsConstructor
public class ComprehensiveScoringController {

    private final ComprehensiveScoringService comprehensiveScoringService;

    /**
     * 运行综合打分
     *
     * @param request 可选参数：
     *   - tradeDate: 交易日 (格式: YYYY-MM-DD)
     *   - persist: 是否落库 (默认true)
     *   - lookbackDays: 回看天数 (默认520)
     *   - threads: 并发线程数 (默认CPU核心数)
     *   - includeItems: 是否返回明细列表 (默认false)
     */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(comprehensiveScoringService.runBatch(request != null ? request : Map.of()));
    }

    /**
     * 查询综合打分结果
     *
     * @param tradeDate 交易日 (可选，默认最新)
     * @param page 页码
     * @param size 每页数量
     */
    @GetMapping("/scores")
    public ApiResponse<Map<String, Object>> scores(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // 使用 StockTrendScoreRepository 查询，factor_version = comprehensive_v1
        // 这里简化处理，实际可以创建专用的Repository方法
        Map<String, Object> result = Map.of(
                "trade_date", tradeDate != null ? tradeDate.toString() : "",
                "message", "请使用 POST /api/scoring/comprehensive/run 先运行打分",
                "list", java.util.List.of(),
                "total", 0L
        );
        return ApiResponse.success(result);
    }
}
