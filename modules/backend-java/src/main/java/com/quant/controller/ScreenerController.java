package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.ScreenerService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
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

    @PostMapping("/templates/save")
    public ApiResponse<Map<String, Object>> saveTemplate(@RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") != null ? Long.valueOf(String.valueOf(body.get("userId"))) : 1L;
        String name = body.get("name") != null ? String.valueOf(body.get("name")) : "";
        String keyword = body.get("keyword") != null ? String.valueOf(body.get("keyword")) : null;
        String exchange = body.get("exchange") != null ? String.valueOf(body.get("exchange")) : null;
        String industry = body.get("industry") != null ? String.valueOf(body.get("industry")) : null;
        Integer minListingDays = body.get("minListingDays") != null
                ? Integer.valueOf(String.valueOf(body.get("minListingDays"))) : null;
        return ApiResponse.success(screenerService.saveTemplate(userId, name, keyword, exchange, industry, minListingDays));
    }

    @GetMapping("/templates/list")
    public ApiResponse<List<Map<String, Object>>> listTemplates(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(screenerService.listTemplates(userId != null ? userId : 1L));
    }

    @PostMapping("/templates/delete")
    public ApiResponse<Map<String, Object>> deleteTemplate(@RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") != null ? Long.valueOf(String.valueOf(body.get("userId"))) : 1L;
        Long id = body.get("id") != null ? Long.valueOf(String.valueOf(body.get("id"))) : 0L;
        return ApiResponse.success(screenerService.deleteTemplate(userId, id));
    }
}
