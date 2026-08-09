package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.FactorResearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 因子研究面板。
 */
@RestController
@RequestMapping("/api/factor")
@RequiredArgsConstructor
public class FactorResearchController {

    private final FactorResearchService factorResearchService;

    @GetMapping("/research")
    public ApiResponse<Map<String, Object>> research(
            @RequestParam(required = false) String strategy,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer holdingDays) {
        return ApiResponse.success(factorResearchService.research(strategy, startDate, endDate, holdingDays));
    }

    /** QP-010：统一因子研究报告（T+1/T+2/T+3/T+5 并列 Pearson IC/Rank IC/ICIR/正率/单调性/spread）。 */
    @GetMapping("/unified-report")
    public ApiResponse<Map<String, Object>> unifiedReport(
            @RequestParam(required = false) String strategy,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(factorResearchService.unifiedReport(strategy, startDate, endDate));
    }

    /**
     * V2-MP-022：完整 PIT 因子截面与 raw forward return IC（权威路径）。
     * 输入 MP-016 feature snapshot，输出含 snapshot/schema/label/metricScope/sample funnel；
     * EXECUTABLE 口径需 labelVersion。
     */
    @GetMapping("/snapshot-research")
    public ApiResponse<Map<String, Object>> snapshotResearch(
            @RequestParam String strategy,
            @RequestParam String snapshotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer holdingDays,
            @RequestParam(required = false) String labelVersion,
            @RequestParam(required = false) String featureVersion) {
        return ApiResponse.success(factorResearchService.snapshotResearch(
                strategy, snapshotId, startDate, endDate, holdingDays, labelVersion, featureVersion));
    }
}
