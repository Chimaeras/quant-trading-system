package com.quant.service.llm;

import com.quant.config.LlmProperties;
import com.quant.dto.llm.LlmConfigDTO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LlmConfigService {
    private final AtomicReference<LlmRuntimeConfig> runtimeRef;

    public LlmConfigService(LlmProperties props) {
        this.runtimeRef = new AtomicReference<>(new LlmRuntimeConfig(
                props.getBaseUrl(),
                props.getApiKey(),
                props.getModel(),
                props.getTemperature(),
                props.getMaxTokens(),
                props.isEnableStream(),
                props.getConnectTimeoutMs(),
                props.getReadTimeoutMs()
        ));
    }

    public LlmRuntimeConfig getRuntimeConfig() {
        return runtimeRef.get();
    }

    public Map<String, Object> getMaskedConfig() {
        LlmRuntimeConfig cfg = runtimeRef.get();
        return Map.of(
                "baseUrl", nullSafe(cfg.getBaseUrl()),
                "apiKey", mask(cfg.getApiKey()),
                "hasApiKey", cfg.getApiKey() != null && !cfg.getApiKey().isBlank(),
                "model", nullSafe(cfg.getModel()),
                "temperature", cfg.getTemperature(),
                "maxTokens", cfg.getMaxTokens(),
                "enableStream", cfg.isEnableStream()
        );
    }

    public Map<String, Object> updateConfig(LlmConfigDTO req) {
        LlmRuntimeConfig cur = runtimeRef.get();
        String newApiKey = req.getApiKey();
        if (newApiKey == null || newApiKey.isBlank()) {
            newApiKey = cur.getApiKey();
        }
        LlmRuntimeConfig next = new LlmRuntimeConfig(
                req.getBaseUrl() != null && !req.getBaseUrl().isBlank() ? req.getBaseUrl().trim() : cur.getBaseUrl(),
                newApiKey,
                req.getModel() != null && !req.getModel().isBlank() ? req.getModel().trim() : cur.getModel(),
                req.getTemperature() != null ? req.getTemperature() : cur.getTemperature(),
                req.getMaxTokens() != null ? req.getMaxTokens() : cur.getMaxTokens(),
                req.getEnableStream() != null ? req.getEnableStream() : cur.isEnableStream(),
                cur.getConnectTimeoutMs(),
                cur.getReadTimeoutMs()
        );
        runtimeRef.set(next);
        return getMaskedConfig();
    }

    private static String mask(String text) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= 8) return "********";
        return text.substring(0, 4) + "********" + text.substring(text.length() - 4);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
