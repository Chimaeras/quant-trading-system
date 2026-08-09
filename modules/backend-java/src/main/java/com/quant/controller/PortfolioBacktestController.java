package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.PortfolioBacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 事件驱动组合回测 API（MP-015 / V2-MP-020）。
 *
 * <p>V2-MP-020 契约：POST /run 异步受理（202，返回 runId + RUNNING），轮询 /{runId}/status
 * 直至 COMPLETED/FAILED；订单按 seq 分页读取，NAV/持仓从子表读取，不再回传单行大 JSON。
 */
@RestController
@RequestMapping("/api/backtest/portfolio")
@RequiredArgsConstructor
public class PortfolioBacktestController {

    private final PortfolioBacktestService portfolioBacktestService;

    /** 异步受理组合回测（202）：predictionRunId 模式从已发布预测闭环取信号。 */
    @PostMapping("/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> run(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> res = portfolioBacktestService.submitRun(body != null ? body : Map.of());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(res));
    }

    /** 运行历史（列表分页）。 */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(portfolioBacktestService.listRuns(page, size));
    }

    /** 运行详情（summary + NAV + 期末持仓 + 状态；订单走 /orders 分页）。 */
    @GetMapping("/{runId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String runId) {
        return ApiResponse.success(portfolioBacktestService.getRun(runId));
    }

    /** 轻量状态轮询（进度/错误）。 */
    @GetMapping("/{runId}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable String runId) {
        return ApiResponse.success(portfolioBacktestService.getRunStatus(runId));
    }

    /** 订单分页读取（seq 升序，size 上限 500）。 */
    @GetMapping("/{runId}/orders")
    public ApiResponse<Map<String, Object>> orders(
            @PathVariable String runId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(portfolioBacktestService.getOrders(runId, page, size));
    }
}
