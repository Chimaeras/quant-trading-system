package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.SimAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final SimAccountService simAccountService;

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info(@RequestParam(required = false) Long userId,
                                                 @RequestParam(required = false) Long sessionId) {
        return ApiResponse.success(simAccountService.getAccountInfo(simAccountService.resolveUserId(userId), sessionId));
    }

    /** V2-MP-021：创建历史演练会话（资金/持仓/订单与在线账户隔离）。 */
    @PostMapping("/session")
    public ApiResponse<Map<String, Object>> createSession(@RequestBody Map<String, Object> body,
                                                          @RequestParam(required = false) Long userId) {
        return ApiResponse.success(simAccountService.createSession(simAccountService.resolveUserId(userId), body));
    }

    /** V2-MP-021：演练会话列表（新建在前）。 */
    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> listSessions(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(simAccountService.listSessions(simAccountService.resolveUserId(userId)));
    }

    /** V2-MP-021：演练会话详情（含该会话持仓与订单）。 */
    @GetMapping("/session/{sessionId}")
    public ApiResponse<Map<String, Object>> sessionDetail(@PathVariable Long sessionId,
                                                          @RequestParam(required = false) Long userId) {
        return ApiResponse.success(simAccountService.getSessionDetail(simAccountService.resolveUserId(userId), sessionId));
    }

    /** V2-MP-021：关闭演练会话（禁止新增订单）。 */
    @PostMapping("/session/{sessionId}/close")
    public ApiResponse<Map<String, Object>> closeSession(@PathVariable Long sessionId,
                                                         @RequestParam(required = false) Long userId) {
        return ApiResponse.success(simAccountService.closeSession(simAccountService.resolveUserId(userId), sessionId));
    }
}
