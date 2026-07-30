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
}
