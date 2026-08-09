package com.quant.exception;

import com.quant.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.apache.catalina.connector.ClientAbortException;

import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * V2-MP-028：生成短 errorId（ERR-<code>-<8hex>），随错误响应返回并写入服务端日志，
     * 供页面展示/用户反馈/日志关联。不以时间作健康判断，仅作请求维度可追溯标识。
     */
    private static String newErrorId(int code) {
        return "ERR-" + code + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static com.quant.dto.ApiResponse<Void> error(int code, String message) {
        String errorId = newErrorId(code);
        log.warn("errorId={} code={} message={}", errorId, code, message);
        return com.quant.dto.ApiResponse.error(code, message, errorId);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        return error(400, message);
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorized(UnauthorizedException e) {
        return error(401, e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        return error(400, e.getMessage());
    }

    /** 资源不存在 → 真实 HTTP 404（V2-MP-008/030，不再用 200 包 code=404）。 */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(ResourceNotFoundException e) {
        return error(404, e.getMessage());
    }

    /** 状态冲突/非法状态转换 → 真实 HTTP 409（V2-MP-008 任务状态机、V2-MP-018 晋级）。 */
    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConflict(ConflictException e) {
        return error(409, e.getMessage());
    }

    /** 并发上限已满 / 限流 → HTTP 429（V2-MP-030）。 */
    @ExceptionHandler(TooManyRequestsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<Void> handleTooManyRequests(TooManyRequestsException e) {
        return error(429, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return error(400, e.getMessage());
    }

    /** 静态资源 404 时不记录日志，避免刷屏（多为 SPA 路由或 favicon 等） */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        String errorId = newErrorId(404);
        log.warn("errorId={} code=404 静态资源不存在", errorId);
        return ResponseEntity.status(404).body(ApiResponse.error(404, "资源不存在", errorId));
    }

    /**
     * 客户端在响应写完前关闭连接（浏览器取消、axios 超时、代理断开等），不是业务错误。
     * 若按 ERROR 打全栈会刷屏；此处降级为 warn。
     */
    @ExceptionHandler(ClientAbortException.class)
    public ResponseEntity<Void> handleClientAbort(ClientAbortException e) {
        log.warn("客户端中止连接(响应可能未完整送达): {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception e) {
        // 完整堆栈仅记录服务端日志；响应不回传 e.getMessage()，避免泄露 SQL/类名/内部细节
        String errorId = newErrorId(500);
        log.error("errorId={} 系统异常: {}", errorId, e.getMessage(), e);
        return ApiResponse.error(500, "服务器内部错误，请稍后重试", errorId);
    }
}
