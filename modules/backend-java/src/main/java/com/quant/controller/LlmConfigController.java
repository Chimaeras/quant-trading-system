package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.llm.LlmConfigDTO;
import com.quant.service.llm.LlmConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmConfigController {
    private final LlmConfigService llmConfigService;

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        return ApiResponse.success(llmConfigService.getMaskedConfig());
    }

    @PostMapping("/config")
    public ApiResponse<Map<String, Object>> saveConfig(@RequestBody(required = false) LlmConfigDTO request) {
        return ApiResponse.success(llmConfigService.updateConfig(request != null ? request : new LlmConfigDTO()));
    }
}
