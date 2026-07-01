package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.SimAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final SimAccountService simAccountService;

    @PostMapping("/place")
    public ApiResponse<Map<String, Object>> place(
            @RequestBody Map<String, Object> body,
            @RequestParam(required = false) Long userId) {
        return ApiResponse.success(simAccountService.placeOrder(simAccountService.resolveUserId(userId), body));
    }
}
