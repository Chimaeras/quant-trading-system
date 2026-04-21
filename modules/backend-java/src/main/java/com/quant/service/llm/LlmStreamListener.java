package com.quant.service.llm;

public interface LlmStreamListener {
    void onDelta(String delta);
    void onComplete(String fullAnswer, String finishReason);
    void onError(String message);
}
