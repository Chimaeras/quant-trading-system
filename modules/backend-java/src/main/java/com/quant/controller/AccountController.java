package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> getAccountInfo(
            @AuthenticationPrincipal UserDetails userDetails) {
        Map<String, Object> info = accountService.getAccountInfo(userDetails.getUsername());
        return ApiResponse.success(info);
    }
}
