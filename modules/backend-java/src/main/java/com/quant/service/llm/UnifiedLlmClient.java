package com.quant.service.llm;

import com.quant.dto.llm.ChatMessageDTO;

import java.util.List;

public interface UnifiedLlmClient {
    LlmCompletionResult chat(LlmRuntimeConfig config, List<ChatMessageDTO> messages, String model, Double temperature, Integer maxTokens);
    void chatStream(LlmRuntimeConfig config, List<ChatMessageDTO> messages, String model, Double temperature, Integer maxTokens, LlmStreamListener listener);
}
