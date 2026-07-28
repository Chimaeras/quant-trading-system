package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.ScreenerService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 条件选股器。
 */
@RestController
@RequestMapping("/api/screener")
@RequiredArgsConstructor
public class ScreenerController {

    private final ScreenerService screenerService;

    @GetMapping("/run")
    public ApiResponse<Map<String, Object>> run(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) Integer minListingDays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(screenerService.screen(
                keyword, exchange, industry, minListingDays, referenceDate, page, size));
    }
}
