package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.exception.BusinessException;
import com.quant.research.ResearchConvention;
import com.quant.service.PredictionService;
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
 * 每日双轨预测快照 API（MP-011）。
 */
@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictionService;

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> daily(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "2") int horizon) {
        return ApiResponse.success(predictionService.getDaily(date, horizon));
    }

    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "2") int horizon) {
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)) {
            throw new BusinessException("horizon 仅支持 2 或 3");
        }
        return ApiResponse.success(predictionService.generate(date, horizon));
    }
}
