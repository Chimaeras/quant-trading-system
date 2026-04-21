package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.dto.llm.ChatCompletionRequestDTO;
import com.quant.dto.llm.ChatCompletionResponseDTO;
import com.quant.service.chat.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chatService;

    @PostMapping("/completions")
    public ApiResponse<ChatCompletionResponseDTO> completion(@RequestBody ChatCompletionRequestDTO request) {
        return ApiResponse.success(chatService.chatCompletion(request));
    }

    @GetMapping("/stream")
    public SseEmitter stream(
            @RequestParam(required = false) String sessionId,
            @RequestParam String message,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer maxTokens
    ) {
        ChatCompletionRequestDTO req = new ChatCompletionRequestDTO();
        req.setSessionId(sessionId);
        req.setMessage(message);
        req.setModel(model);
        req.setTemperature(temperature);
        req.setMaxTokens(maxTokens);
        return chatService.streamCompletion(req);
    }
}
