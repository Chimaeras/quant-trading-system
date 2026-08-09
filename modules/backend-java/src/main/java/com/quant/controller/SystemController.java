package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.SystemReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统就绪度 API（V2-MP-028）。
 *
 * <p>顶部状态由 {@code health}（db/日历/行情时效/数据门禁）聚合而来，
 * 市场时段独立返回但不参与健康判定；本机时间不作为系统健康。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemReadinessService systemReadinessService;

    @GetMapping("/readiness")
    public ApiResponse<Map<String, Object>> readiness() {
        return ApiResponse.success(systemReadinessService.readiness());
    }
}
