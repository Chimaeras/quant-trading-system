package com.quant.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.llm.ChatMessageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenAiCompatibleClient implements UnifiedLlmClient {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public LlmCompletionResult chat(
            LlmRuntimeConfig config,
            List<ChatMessageDTO> messages,
            String model,
            Double temperature,
            Integer maxTokens
    ) {
        try {
            String payload = buildPayload(
                    messages,
                    model != null ? model : config.getModel(),
                    temperature != null ? temperature : config.getTemperature(),
                    maxTokens != null ? maxTokens : config.getMaxTokens(),
                    false
            );
            RestTemplate restTemplate = createRestTemplate(config);
            ResponseEntity<String> response = null;
            String lastError = "";
            for (String endpoint : completionEndpoints(config.getBaseUrl())) {
                HttpEntity<String> entity = new HttpEntity<>(payload, buildHeaders(config.getApiKey(), false));
                response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);
                if (response.getStatusCode().value() >= 200 && response.getStatusCode().value() < 300) {
                    break;
                }
                if (response.getStatusCode().value() == 404) {
                    lastError = "endpoint 404: " + endpoint;
                    continue;
                }
                throw new RuntimeException("LLM请求失败(" + response.getStatusCode().value() + "): " + trimText(response.getBody()));
            }
            if (response == null || response.getStatusCode().value() < 200 || response.getStatusCode().value() >= 300) {
                throw new RuntimeException("LLM请求失败: " + (lastError.isBlank() ? "未匹配可用 completions 路径" : lastError));
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0
                    ? root.path("choices").get(0) : null;
            String answer = choice != null ? choice.path("message").path("content").asText("") : "";
            String finishReason = choice != null && !choice.path("finish_reason").isMissingNode()
                    ? choice.path("finish_reason").asText("") : "";
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.isObject() ? intOrNull(usage.path("prompt_tokens")) : null;
            Integer completionTokens = usage.isObject() ? intOrNull(usage.path("completion_tokens")) : null;
            Integer totalTokens = usage.isObject() ? intOrNull(usage.path("total_tokens")) : null;
            return new LlmCompletionResult(answer, finishReason, promptTokens, completionTokens, totalTokens);
        } catch (Exception e) {
            log.warn("LLM non-stream 调用失败: {}", e.getMessage());
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "本地LLM调用失败，请检查BaseUrl/API Key/模型名称";
            }
            throw new RuntimeException(msg, e);
        }
    }

    @Override
    public void chatStream(
            LlmRuntimeConfig config,
            List<ChatMessageDTO> messages,
            String model,
            Double temperature,
            Integer maxTokens,
            LlmStreamListener listener
    ) {
        StringBuilder full = new StringBuilder();
        String finishReason = "";
        try {
            String payload = buildPayload(
                    messages,
                    model != null ? model : config.getModel(),
                    temperature != null ? temperature : config.getTemperature(),
                    maxTokens != null ? maxTokens : config.getMaxTokens(),
                    true
            );
            RestTemplate restTemplate = createRestTemplate(config);
            ResponseEntity<String> response = null;
            String lastError = "";
            for (String endpoint : completionEndpoints(config.getBaseUrl())) {
                HttpEntity<String> entity = new HttpEntity<>(payload, buildHeaders(config.getApiKey(), true));
                response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);
                if (response.getStatusCode().value() >= 200 && response.getStatusCode().value() < 300 && response.getBody() != null) {
                    break;
                }
                if (response.getStatusCode().value() == 404) {
                    lastError = "endpoint 404: " + endpoint;
                    continue;
                }
                throw new RuntimeException("LLM流式请求失败(" + response.getStatusCode().value() + "): " + trimText(response.getBody()));
            }
            if (response == null || response.getStatusCode().value() < 200 || response.getStatusCode().value() >= 300) {
                throw new RuntimeException("LLM流式请求失败: " + (lastError.isBlank() ? "未匹配可用 completions 路径" : lastError));
            }
            String raw = response.getBody();
            if (raw == null || raw.isBlank()) {
                listener.onComplete("", "");
                return;
            }
            // 兼容两种返回：SSE 分片 / 普通 JSON
            if (raw.contains("\ndata:") || raw.startsWith("data:")) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8
                ))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isBlank()) continue;
                        if ("[DONE]".equals(data)) {
                            listener.onComplete(full.toString(), finishReason);
                            return;
                        }
                        JsonNode root = objectMapper.readTree(data);
                        JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0
                                ? root.path("choices").get(0) : null;
                        if (choice == null) continue;
                        String delta = choice.path("delta").path("content").asText("");
                        if (!delta.isEmpty()) {
                            full.append(delta);
                            listener.onDelta(delta);
                        }
                        String fr = choice.path("finish_reason").asText("");
                        if (!fr.isBlank() && !"null".equalsIgnoreCase(fr)) finishReason = fr;
                    }
                }
                listener.onComplete(full.toString(), finishReason);
                return;
            }
            // 普通 JSON（有些网关即便 stream=true 也会一次性返回）
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0
                    ? root.path("choices").get(0) : null;
            String answer = choice != null ? choice.path("message").path("content").asText("") : "";
            String fr = choice != null ? choice.path("finish_reason").asText("") : "";
            if (!answer.isEmpty()) {
                listener.onDelta(answer);
            }
            listener.onComplete(answer, fr);
        } catch (Exception e) {
            log.warn("LLM stream 调用失败: {}", e.getMessage());
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "本地LLM流式调用失败，请检查BaseUrl/API Key/模型名称";
            }
            listener.onError(msg);
        }
    }

    private RestTemplate createRestTemplate(LlmRuntimeConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    private HttpHeaders buildHeaders(String apiKey, boolean stream) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(stream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON));
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
        return headers;
    }

    private String buildPayload(List<ChatMessageDTO> messages, String model, Double temperature, Integer maxTokens, boolean stream) throws Exception {
        List<Map<String, String>> msg = new ArrayList<>();
        if (messages != null) {
            for (ChatMessageDTO m : messages) {
                if (m == null || m.getContent() == null || m.getContent().isBlank()) continue;
                Map<String, String> one = new HashMap<>();
                one.put("role", (m.getRole() == null || m.getRole().isBlank()) ? "user" : m.getRole());
                one.put("content", m.getContent());
                msg.add(one);
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", msg);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", stream);
        return objectMapper.writeValueAsString(payload);
    }

    private static String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("LLM baseUrl 未配置");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    private static List<String> completionEndpoints(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("LLM baseUrl 未配置");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        List<String> urls = new ArrayList<>();
        if (base.endsWith("/chat/completions")) {
            urls.add(base);
            return urls;
        }
        if (base.contains("/v1")) {
            urls.add(joinUrl(base, "/chat/completions"));
        } else {
            urls.add(joinUrl(base, "/v1/chat/completions"));
        }
        urls.add(joinUrl(base, "/chat/completions"));
        urls.add(joinUrl(base, "/api/paas/v4/chat/completions"));
        return urls;
    }

    private static Integer intOrNull(JsonNode n) {
        return n != null && n.isNumber() ? n.asInt() : null;
    }

    private static String trimText(String text) {
        if (text == null) return "";
        return text.length() <= 220 ? text : text.substring(0, 220) + "...";
    }
}
