package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.MultiDimScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/scoring/multi-dim")
@RequiredArgsConstructor
public class MultiDimScoringController {

    private final MultiDimScoringService multiDimScoringService;

    /** 多维度综合打分（技术面文档版+基本面+政策+轮动+行业对比） */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> request) {
        return ApiResponse.success(multiDimScoringService.run(request != null ? request : Map.of()));
    }

    @GetMapping("/scores")
    public ApiResponse<Map<String, Object>> scores(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(multiDimScoringService.listScores(tradeDate, page, size));
    }
}
