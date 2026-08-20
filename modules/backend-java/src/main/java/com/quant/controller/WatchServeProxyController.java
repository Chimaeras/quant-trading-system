package com.quant.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 将前端 /pyapi/** 反向代理到本地 watch-serve（默认 127.0.0.1:8790）。
 * 与 Vite 开发代理语义对齐，使 Spring Boot 同端口托管的监控页可访问盯盘 API。
 */
@RestController
public class WatchServeProxyController {

    private final RestClient restClient;
    private final String watchServeBase;

    public WatchServeProxyController(
            @Value("${quant.watch-serve.base-url:http://127.0.0.1:8790}") String watchServeBase) {
        this.watchServeBase = watchServeBase.replaceAll("/$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @RequestMapping(value = "/pyapi/**", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS
    })
    public ResponseEntity<byte[]> proxy(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] requestBody) {
        String uri = request.getRequestURI();
        String prefix = "/pyapi";
        String path = uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
        if (path.isEmpty()) {
            path = "/";
        }
        String query = request.getQueryString();
        String target = watchServeBase + path + (query == null || query.isBlank() ? "" : "?" + query);

        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(target);
            if (request.getContentType() != null) {
                spec.header(HttpHeaders.CONTENT_TYPE, request.getContentType());
            }
            if (requestBody != null && requestBody.length > 0) {
                spec.body(requestBody);
            }
            return spec.retrieve()
                    .onStatus(status -> status.isError(), (upstreamRequest, upstreamResponse) -> {
                        // 观察池参数错误等 4xx 应原样透传给页面，不能伪装成代理 502。
                    })
                    .toEntity(byte[].class);
        } catch (RestClientException ex) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
            byte[] body = ("{\"ok\":false,\"error\":\"watch_serve_unreachable\",\"message\":\""
                    + escapeJson(ex.getMessage()) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.status(502).headers(headers).body(body);
        }
    }

    private static String escapeJson(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
