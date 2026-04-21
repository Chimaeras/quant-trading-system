package com.quant.dto.llm;

import lombok.Data;

import java.util.List;

@Data
public class ChatCompletionRequestDTO {
    private String sessionId;
    private String message;
    private List<ChatMessageDTO> messages;
    private String model;
    private Double temperature;
    private Integer maxTokens;
}
