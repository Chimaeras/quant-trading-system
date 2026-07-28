package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 持仓与选股信号对账。
 */
@RestController
@RequestMapping("/api/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;

    @GetMapping("/position")
    public ApiResponse<Map<String, Object>> position(
            @RequestParam(required = false) Long userId,
            @RequestParam String resultId,
            @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.success(reconcileService.reconcile(
                userId != null ? userId : 1L, resultId, topN));
    }
}
