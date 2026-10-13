package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.MinuteBarItem;
import com.quant.service.MarketDataMinuteService;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分钟级行情接口（D-STALE-BAR-01 修复 4）。
 *
 * <p>路径放在 `/api/data` 命名空间下，与 hot-stocks / financial / margin 等既有
 * 数据面接口一致；写入语义也沿用「外部脚本批量导入 + 幂等 upsert」的既有约定。
 *
 * <p>调用方是 Python 侧盯盘进程：每轮把新收盘的 5 分钟 bar 提交上来沉淀，
 * 启动预热时再按代码批量取回连续序列。
 */
@RestController
@RequestMapping("/api/data/minute-bars")
@RequiredArgsConstructor
public class MarketDataMinuteController {

    private final MarketDataMinuteService service;

    /**
     * 批量提交已收盘的 5 分钟 bar（幂等，可反复提交同一根）。
     *
     * <p>坏 bar 只计数跳过而不整批失败：盯盘一轮可能提交数百根，一根脏数据不该
     * 让整轮沉淀丢失。
     */
    @PostMapping("/bulk")
    public ApiResponse<Map<String, Object>> bulk(@RequestBody List<MinuteBarItem> items) {
        return ApiResponse.success(service.bulkUpsert(items));
    }

    /**
     * 按代码取最近 N 根 bar，供盯盘启动预热使用。
     *
     * <p>用 POST 而非 GET：观察池有 200+ 代码，放在 query string 会撞 URL 长度限制，
     * 且逐个代码请求会把一次预热放大成数百次往返。
     */
    @PostMapping("/recent")
    public ApiResponse<Map<String, Object>> recent(@RequestBody(required = false) RecentRequest request) {
        RecentRequest req = request == null ? new RecentRequest() : request;
        return ApiResponse.success(service.recentByCode(
                req.getCodes(), req.getPeriodMin(), req.getLimitPerCode(), req.getLookbackDays()));
    }

    /** 入库情况：是否在持续沉淀分钟线。 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestParam(required = false) Integer periodMin) {
        return ApiResponse.success(service.stats(periodMin));
    }

    /** 清理窗口外的陈旧分钟线。分钟线是本地资产，保留期默认一年。 */
    @PostMapping("/prune")
    public ApiResponse<Map<String, Object>> prune(@RequestParam(required = false) Integer periodMin,
                                                 @RequestParam(required = false) Integer keepDays) {
        return ApiResponse.success(service.prune(periodMin, keepDays));
    }

    @Data
    public static class RecentRequest {
        private List<String> codes;
        @JsonProperty("period_min")
        private Integer periodMin;
        @JsonProperty("limit_per_code")
        private Integer limitPerCode;
        @JsonProperty("lookback_days")
        private Integer lookbackDays;
    }
}
