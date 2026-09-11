package com.quant.controller;

import com.quant.security.UserIdResolver;
import com.quant.service.WatchlistAlertService;
import com.quant.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R33-P2-6 回归：自选股池三个写接口的 userId 必须容错解析。
 *
 * <p>修复前 {@code Long.valueOf(String.valueOf(body.get("userId")))} 对 "1.0" 抛
 * NumberFormatException（前端 JSON.stringify 整型 id 不会出问题，但 curl/Postman 手写
 * 或某些网关会序列化成浮点），对非数字字符串抛 ClassCastException；两者都没有被
 * GlobalExceptionHandler 映射，用户看到 500。
 */
class WatchlistControllerBodyValueTest {

    private WatchlistService watchlistService;
    private WatchlistAlertService watchlistAlertService;
    private UserIdResolver userIdResolver;
    private WatchlistController controller;

    @BeforeEach
    void setUp() {
        watchlistService = mock(WatchlistService.class);
        watchlistAlertService = mock(WatchlistAlertService.class);
        userIdResolver = mock(UserIdResolver.class);
        when(userIdResolver.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
        controller = new WatchlistController(watchlistService, watchlistAlertService, userIdResolver);
    }

    @Test
    void addRejectsNonNumericUserIdAsBadRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", "abc");
        body.put("stockCode", "600519");
        body.put("exchangeType", "SSE");

        assertThatThrownBy(() -> controller.add(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void addAcceptsIntegralFloatingUserId() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 1.0);
        body.put("stockCode", "600519");
        body.put("exchangeType", "SSE");

        controller.add(body);

        verify(watchlistService).add(eq(1L), eq("600519"), eq("SSE"), any());
    }

    @Test
    void removeRejectsNonNumericUserIdAsBadRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", "用户一");
        body.put("stockCode", "600519");
        body.put("exchangeType", "SSE");

        assertThatThrownBy(() -> controller.remove(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void batchAddRejectsNonNumericUserIdAsBadRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", "1.5");
        body.put("text", "600519,SSE");

        assertThatThrownBy(() -> controller.batchAdd(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void addTreatsMissingUserIdAsAnonymous() {
        when(watchlistService.add(any(), any(), any(), any())).thenReturn(Map.of("added", true));

        Map<String, Object> body = new HashMap<>();
        body.put("stockCode", "600519");
        body.put("exchangeType", "SSE");

        controller.add(body);

        verify(watchlistService).add(null, "600519", "SSE", null);
    }
}
