package com.quant.controller;

import com.quant.security.UserIdResolver;
import com.quant.service.ScreenerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R33-P2-6 回归：请求体里的 id / 数值字段必须按 400 语义报错，不得冒泡成 500。
 *
 * <p>修复前控制器直接强转（{@code (Long) body.get("userId")} 或
 * {@code Long.valueOf(String.valueOf(...))}）：客户端把 id 序列化成浮点（"1.0"）就抛
 * NumberFormatException，传非数字字符串就抛 ClassCastException —— 两者都不在
 * GlobalExceptionHandler 的映射表里，用户看到的是 500。
 */
class ScreenerControllerBodyValueTest {

    private ScreenerService screenerService;
    private UserIdResolver userIdResolver;
    private ScreenerController controller;

    @BeforeEach
    void setUp() {
        screenerService = mock(ScreenerService.class);
        userIdResolver = mock(UserIdResolver.class);
        // 鉴权关闭时 resolve 原样返回入参
        when(userIdResolver.resolve(any())).thenAnswer(inv -> inv.getArgument(0));
        controller = new ScreenerController(screenerService, userIdResolver);
    }

    @Test
    void saveTemplateRejectsNonNumericUserIdAsBadRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", "abc");
        body.put("name", "银行筛选");

        assertThatThrownBy(() -> controller.saveTemplate(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void saveTemplateAcceptsIntegralFloatingUserId() {
        when(screenerService.saveTemplate(eq(1L), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", 1L));

        Map<String, Object> body = new HashMap<>();
        body.put("userId", "1.0");
        body.put("name", "银行筛选");

        controller.saveTemplate(body);

        // "1.0" 是整数值的浮点字面量，应被接受而不是报错
        verify(screenerService).saveTemplate(eq(1L), eq("银行筛选"), isNull(), isNull(), isNull(),
                isNull(), isNull());
    }

    @Test
    void saveTemplateRejectsFractionalMinListingDays() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "银行筛选");
        body.put("minListingDays", 10.5);

        assertThatThrownBy(() -> controller.saveTemplate(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minListingDays");
    }

    @Test
    void saveTemplateTreatsBlankUserIdAsAbsent() {
        when(screenerService.saveTemplate(isNull(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", 2L));

        Map<String, Object> body = new HashMap<>();
        body.put("userId", "   ");
        body.put("name", "银行筛选");

        controller.saveTemplate(body);

        verify(screenerService).saveTemplate(isNull(), eq("银行筛选"), isNull(), isNull(), isNull(),
                isNull(), isNull());
    }

    @Test
    void deleteTemplateDefaultsMissingIdToZero() {
        when(screenerService.deleteTemplate(any(), any())).thenReturn(Map.of("deleted", false));

        Map<String, Object> body = new HashMap<>();
        body.put("userId", 1);

        controller.deleteTemplate(body);

        verify(screenerService).deleteTemplate(1L, 0L);
    }

    @Test
    void deleteTemplateRejectsGarbageId() {
        Map<String, Object> body = new HashMap<>();
        body.put("id", "第一号");

        assertThatThrownBy(() -> controller.deleteTemplate(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void saveTemplateReturnsServiceResultUntouched() {
        when(screenerService.saveTemplate(eq(7L), eq("t"), eq("kw"), eq("SSE"), eq("银行"), eq(60),
                eq(Boolean.TRUE)))
                .thenReturn(Map.of("id", 3L));

        Map<String, Object> body = new HashMap<>();
        body.put("userId", 7);
        body.put("name", "t");
        body.put("keyword", "kw");
        body.put("exchange", "SSE");
        body.put("industry", "银行");
        body.put("minListingDays", 60);
        body.put("excludeSt", true);

        assertThat(controller.saveTemplate(body).getData()).containsEntry("id", 3L);
    }
}
