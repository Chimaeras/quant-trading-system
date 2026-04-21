package com.quant.service.chat;

import com.quant.config.LlmProperties;
import com.quant.dto.llm.*;
import com.quant.service.llm.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final LlmConfigService llmConfigService;
    private final UnifiedLlmClient llmClient;
    private final ChatSessionStore chatSessionStore;
    private final LlmProperties llmProperties;

    public ChatCompletionResponseDTO chatCompletion(ChatCompletionRequestDTO request) {
        String sessionId = normalizeSessionId(request.getSessionId());
        String userText = normalizeText(request.getMessage());
        if (userText.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        List<ChatMessageDTO> history = buildHistory(sessionId, request.getMessages());
        ChatMessageDTO userMsg = new ChatMessageDTO("user", userText);
        history.add(userMsg);
        chatSessionStore.append(sessionId, userMsg, llmProperties.getSessionMaxMessages());

        LlmRuntimeConfig runtime = llmConfigService.getRuntimeConfig();
        LlmCompletionResult result = llmClient.chat(runtime, history, request.getModel(), request.getTemperature(), request.getMaxTokens());
        ChatMessageDTO assistantMsg = new ChatMessageDTO("assistant", result.getAnswer() == null ? "" : result.getAnswer());
        chatSessionStore.append(sessionId, assistantMsg, llmProperties.getSessionMaxMessages());
        return new ChatCompletionResponseDTO(
                sessionId,
                request.getModel() != null && !request.getModel().isBlank() ? request.getModel() : runtime.getModel(),
                assistantMsg.getContent(),
                result.getFinishReason(),
                result.getPromptTokens(),
                result.getCompletionTokens(),
                result.getTotalTokens()
        );
    }

    public SseEmitter streamCompletion(ChatCompletionRequestDTO request) {
        String sessionId = normalizeSessionId(request.getSessionId());
        String userText = normalizeText(request.getMessage());
        if (userText.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        List<ChatMessageDTO> history = buildHistory(sessionId, request.getMessages());
        ChatMessageDTO userMsg = new ChatMessageDTO("user", userText);
        history.add(userMsg);
        chatSessionStore.append(sessionId, userMsg, llmProperties.getSessionMaxMessages());

        LlmRuntimeConfig runtime = llmConfigService.getRuntimeConfig();
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> llmClient.chatStream(
                runtime,
                history,
                request.getModel(),
                request.getTemperature(),
                request.getMaxTokens(),
                new LlmStreamListener() {
                    @Override
                    public void onDelta(String delta) {
                        sendEvent(emitter, "delta", new ChatStreamEventDTO("delta", sessionId, delta, null, null, null));
                    }

                    @Override
                    public void onComplete(String fullAnswer, String finishReason) {
                        chatSessionStore.append(sessionId, new ChatMessageDTO("assistant", fullAnswer), llmProperties.getSessionMaxMessages());
                        sendEvent(emitter, "done", new ChatStreamEventDTO("done", sessionId, null, fullAnswer, finishReason, null));
                        emitter.complete();
                    }

                    @Override
                    public void onError(String message) {
                        sendEvent(emitter, "error", new ChatStreamEventDTO("error", sessionId, null, null, null, message));
                        emitter.completeWithError(new RuntimeException(message));
                    }
                }
        ));
        return emitter;
    }

    private List<ChatMessageDTO> buildHistory(String sessionId, List<ChatMessageDTO> messages) {
        if (messages != null && !messages.isEmpty()) {
            List<ChatMessageDTO> fromReq = new ArrayList<>();
            for (ChatMessageDTO m : messages) {
                if (m == null) continue;
                String content = normalizeText(m.getContent());
                if (content.isBlank()) continue;
                fromReq.add(new ChatMessageDTO(
                        (m.getRole() == null || m.getRole().isBlank()) ? "user" : m.getRole(),
                        content
                ));
            }
            return fromReq;
        }
        return new ArrayList<>(chatSessionStore.getHistory(sessionId));
    }

    private static String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "s-" + UUID.randomUUID() : sessionId.trim();
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.trim();
    }

    private static void sendEvent(SseEmitter emitter, String event, ChatStreamEventDTO data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ignored) {
            emitter.complete();
        }
    }
}
