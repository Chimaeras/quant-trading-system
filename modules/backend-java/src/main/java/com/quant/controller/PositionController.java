package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.service.PositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/position")
@RequiredArgsConstructor
public class PositionController {

    private final PositionService positionService;

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> getPositionList(
            @AuthenticationPrincipal UserDetails userDetails) {
        List<Map<String, Object>> list = positionService.getPositionList(userDetails.getUsername());
        return ApiResponse.success(list);
    }
}
