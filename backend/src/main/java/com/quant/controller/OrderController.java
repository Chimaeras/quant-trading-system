package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/place")
    public ApiResponse<Map<String, Object>> placeOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, Object> orderRequest) {
        Map<String, Object> result = orderService.placeOrder(userDetails.getUsername(), orderRequest);
        return ApiResponse.success(result);
    }
}
