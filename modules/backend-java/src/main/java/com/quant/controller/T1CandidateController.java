package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.T1CandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * T+1 次日候选核心闭环：T 日收盘生成候选 Top10 → 次日核销 → 历史回溯。
 */
@RestController
@RequestMapping("/api/research/t1")
@RequiredArgsConstructor
public class T1CandidateController {

    private final T1CandidateService t1CandidateService;

    /** T 日收盘生成「次日上涨候选 TopN」并落库。 */
    @PostMapping("/candidates")
    public ApiResponse<Map<String, Object>> generate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.success(t1CandidateService.generateCandidates(tradeDate, topN));
    }

    /** T+1 结束后核销：回填候选次日表现并聚合命中率。 */
    @PostMapping("/settle")
    public ApiResponse<Map<String, Object>> settle(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        return ApiResponse.success(t1CandidateService.settleCandidates(tradeDate));
    }

    /** 按日回溯候选（含核销结果）。 */
    @GetMapping("/candidates/{tradeDate}")
    public ApiResponse<Map<String, Object>> get(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        return ApiResponse.success(t1CandidateService.getCandidates(tradeDate));
    }

    /** 最近生成的候选列表（摘要）。 */
    @GetMapping("/recent")
    public ApiResponse<List<Map<String, Object>>> recent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(t1CandidateService.recentCandidates(limit));
    }
}
