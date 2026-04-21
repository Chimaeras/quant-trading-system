package com.quant.service.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmCompletionResult {
    private String answer;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
}
