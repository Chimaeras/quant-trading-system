package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.research.HistoricalReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 历史重放与标签工厂研究接口（QP-007+）。
 *
 * <p>当前仅暴露股票池健康重放；后续迭代接入 feature_snapshot/label_snapshot。
 */
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class ResearchController {

    private final HistoricalReplayService historicalReplayService;

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
}
