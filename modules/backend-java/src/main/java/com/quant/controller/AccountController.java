package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.SimAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final SimAccountService simAccountService;

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(simAccountService.getAccountInfo(simAccountService.resolveUserId(userId)));
    }
}
