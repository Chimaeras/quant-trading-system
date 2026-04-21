package com.quant.dto.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatCompletionResponseDTO {
    private String sessionId;
    private String model;
    private String answer;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
}
