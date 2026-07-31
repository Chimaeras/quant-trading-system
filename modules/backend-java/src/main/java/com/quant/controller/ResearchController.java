package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.research.HistoricalReplayService;
import com.quant.research.LabelFactoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 历史重放与标签工厂研究接口（QP-007+）。
 *
 * <p>当前暴露股票池健康重放（QP-007）与 T+2/T+3 标签工厂（QP-008）。
 */
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class ResearchController {

    private final HistoricalReplayService historicalReplayService;
    private final LabelFactoryService labelFactoryService;

    /**
     * QP-007：按交易日区间重放 PIT 历史股票池，生成数据集健康报告。
     * 股票池 = PIT 证券主数据 ∩ 当日有行情；统计每日池规模、PIT/行情缺失。
     * 幂等只读，可断点续跑。
     */
    @PostMapping("/replay")
    public ApiResponse<Map<String, Object>> replay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(historicalReplayService.replayPoolHealth(startDate, endDate));
    }

    /**
     * QP-008：为区间内每个交易日生成 T+2/T+3 标签（基于 PIT 股票池）。
     * 保留所有拒单样本不净化；幂等跳过已存在。默认 label_version=label_v1。
     */
    @PostMapping("/labels/build")
    public ApiResponse<Map<String, Object>> buildLabels(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "label_v1") String labelVersion) {
        return ApiResponse.success(labelFactoryService.buildLabels(startDate, endDate, labelVersion));
    }

    /** QP-008：标签覆盖率统计（exec_status 分布）。 */
    @GetMapping("/labels/coverage")
    public ApiResponse<Map<String, Object>> labelCoverage(
            @RequestParam(defaultValue = "label_v1") String labelVersion) {
        return ApiResponse.success(labelFactoryService.buildCoverage(labelVersion));
    }
}
