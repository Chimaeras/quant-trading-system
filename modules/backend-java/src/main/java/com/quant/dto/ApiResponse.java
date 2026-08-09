package com.quant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 统一响应包装（V2-MP-028）：错误响应携带 errorId 供页面展示与日志关联；
 * 成功响应不序列化 errorId，保持 payload 干净。
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    /** V2-MP-028：错误 ID（仅错误响应携带），用于页面展示 + 服务端日志关联。 */
    private String errorId;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, null);
    }

    /** V2-MP-028：错误响应附带 errorId，前端不再吞掉错误为空态。 */
    public static <T> ApiResponse<T> error(int code, String message, String errorId) {
        return new ApiResponse<>(code, message, null, errorId);
    }
}
