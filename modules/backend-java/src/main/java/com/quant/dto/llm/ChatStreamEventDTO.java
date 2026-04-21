package com.quant.dto.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEventDTO {
    private String type;
    private String sessionId;
    private String delta;
    private String answer;
    private String finishReason;
    private String error;
}
