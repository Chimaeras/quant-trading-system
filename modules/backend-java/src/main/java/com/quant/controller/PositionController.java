package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.SimAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/position")
@RequiredArgsConstructor
public class PositionController {

    private final SimAccountService simAccountService;

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long userId) {
        return ApiResponse.success(simAccountService.listPositions(simAccountService.resolveUserId(userId)));
    }
}
