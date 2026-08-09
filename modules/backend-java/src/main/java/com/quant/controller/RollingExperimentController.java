package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.RollingExperimentRunRequest;
import com.quant.research.RollingExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 滚动 purged Walk-Forward 实验（V2-MP-023）。
 * 独立入口，不触碰旧 WalkForwardService（分段稳定性 SEGMENT_STABILITY）。
 * 运行/恢复/列表/详情；窗口参数不可变，逐窗落库可中断恢复。
 */
@RestController
@RequestMapping("/api/research/rolling-experiment")
@RequiredArgsConstructor
public class RollingExperimentController {

    private final RollingExperimentService rollingExperimentService;

    /** 运行（不带 experimentId）或幂等恢复（带 experimentId，参数须一致）。 */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody RollingExperimentRunRequest request) {
        return ApiResponse.success(rollingExperimentService.run(request));
    }

    /** 恢复运行：读取已存参数重建请求，跳过已落库窗口继续。 */
    @PostMapping("/{experimentId}/resume")
    public ApiResponse<Map<String, Object>> resume(@PathVariable String experimentId) {
        return ApiResponse.success(rollingExperimentService.resume(experimentId));
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(rollingExperimentService.list());
    }

    @GetMapping("/{experimentId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String experimentId) {
        return ApiResponse.success(rollingExperimentService.detail(experimentId));
    }
}
