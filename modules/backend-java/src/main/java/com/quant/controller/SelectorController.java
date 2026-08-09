package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.exception.ResourceNotFoundException;
import com.quant.service.OversoldReboundScoringService;
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
    private final OversoldReboundScoringService oversoldReboundScoringService;

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(stockSelectorService.runSelection(request != null ? request : Map.of()));
    }

    @PostMapping("/oversold-rebound/run")
    public ApiResponse<Map<String, Object>> runOversoldRebound(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(oversoldReboundScoringService.run(request != null ? request : Map.of()));
    }

    @GetMapping("/result")
    public ApiResponse<Map<String, Object>> latest(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(required = false) String factorVersion) {
        return ApiResponse.success(stockSelectorService.getLatestResult(tradeDate, factorVersion));
    }

    @GetMapping("/result/{resultId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String resultId) {
        Map<String, Object> data = stockSelectorService.getResultById(resultId);
        if (data == null) throw new ResourceNotFoundException("选股结果不存在: " + resultId);
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
        if (!ok) throw new ResourceNotFoundException("模板不存在: " + templateId);
        return ApiResponse.success(Map.of("deleted", true));
    }
}
