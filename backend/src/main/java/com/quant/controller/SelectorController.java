package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.StockSelectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/selector")
@RequiredArgsConstructor
public class SelectorController {
    private final StockSelectorService stockSelectorService;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(stockSelectorService.runSelection(request != null ? request : Map.of()));
    }

    @GetMapping("/result")
    public ApiResponse<Map<String, Object>> latest(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        return ApiResponse.success(stockSelectorService.getLatestResult(tradeDate));
    }

    @GetMapping("/result/{resultId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String resultId) {
        Map<String, Object> data = stockSelectorService.getResultById(resultId);
        if (data == null) return ApiResponse.error(404, "选股结果不存在");
        return ApiResponse.success(data);
    }

    @GetMapping("/templates")
    public ApiResponse<Object> templates() {
        return ApiResponse.success(stockSelectorService.listTemplates());
    }

    @PostMapping("/templates")
    public ApiResponse<Map<String, Object>> saveTemplate(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(stockSelectorService.saveTemplate(request != null ? request : Map.of()));
    }

    @DeleteMapping("/templates/{templateId}")
    public ApiResponse<Object> deleteTemplate(@PathVariable String templateId) {
        boolean ok = stockSelectorService.deleteTemplate(templateId);
        if (!ok) return ApiResponse.error(404, "模板不存在");
        return ApiResponse.success(Map.of("deleted", true));
    }
}
