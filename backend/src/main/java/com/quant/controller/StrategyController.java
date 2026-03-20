package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.StrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService strategyService;

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> getStrategyList(
            @AuthenticationPrincipal UserDetails userDetails) {
        List<Map<String, Object>> list = strategyService.getStrategyList(userDetails.getUsername());
        return ApiResponse.success(list);
    }
}
