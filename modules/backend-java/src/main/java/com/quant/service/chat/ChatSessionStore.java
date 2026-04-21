package com.quant.service.chat;

import com.quant.dto.llm.ChatMessageDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatSessionStore {
    private final Map<String, Deque<ChatMessageDTO>> sessions = new ConcurrentHashMap<>();

    public List<ChatMessageDTO> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        Deque<ChatMessageDTO> q = sessions.get(sessionId);
        if (q == null) return List.of();
        return new ArrayList<>(q);
    }

    public void append(String sessionId, ChatMessageDTO msg, int maxMessages) {
        if (sessionId == null || sessionId.isBlank() || msg == null) return;
        Deque<ChatMessageDTO> q = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        q.addLast(msg);
        while (q.size() > Math.max(2, maxMessages)) {
            q.pollFirst();
        }
    }
}
