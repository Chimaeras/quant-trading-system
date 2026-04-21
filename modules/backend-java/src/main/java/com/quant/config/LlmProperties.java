package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String baseUrl = "http://localhost:11434/v1";
    private String apiKey = "";
    private String model = "qwen2.5";
    private double temperature = 0.3;
    private int maxTokens = 1024;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 120000;
    private boolean enableStream = true;
    private int sessionMaxMessages = 20;
}
