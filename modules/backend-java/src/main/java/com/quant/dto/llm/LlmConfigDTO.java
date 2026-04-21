package com.quant.dto.llm;

import lombok.Data;

@Data
public class LlmConfigDTO {
    private String baseUrl;
    private String apiKey;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Boolean enableStream;
}
