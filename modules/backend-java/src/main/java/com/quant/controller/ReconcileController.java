package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.security.UserIdResolver;
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
    private final UserIdResolver userIdResolver;

    @GetMapping("/position")
    public ApiResponse<Map<String, Object>> position(
            @RequestParam(required = false) Long userId,
            @RequestParam String resultId,
            @RequestParam(defaultValue = "10") int topN) {
        // MP-010：鉴权开启时 userId 一律取自 AuthContext，忽略客户端 query 参数（防 IDOR）。
        return ApiResponse.success(reconcileService.reconcile(
                userIdResolver.resolve(userId), resultId, topN));
    }
}
