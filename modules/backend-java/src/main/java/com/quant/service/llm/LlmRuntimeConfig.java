package com.quant.service.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmRuntimeConfig {
    private String baseUrl;
    private String apiKey;
    private String model;
    private double temperature;
    private int maxTokens;
    private boolean enableStream;
    private int connectTimeoutMs;
    private int readTimeoutMs;
}
