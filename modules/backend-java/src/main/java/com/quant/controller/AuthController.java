package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return ApiResponse.success(authService.login(
                body.getOrDefault("username", ""),
                body.getOrDefault("password", "")
        ));
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        return ApiResponse.success(authService.register(
                body.getOrDefault("username", ""),
                body.getOrDefault("password", "")
        ));
    }
}
