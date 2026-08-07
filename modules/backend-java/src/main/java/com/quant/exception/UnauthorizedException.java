package com.quant.exception;

/** 未认证或 token 无效；由全局异常处理映射为 HTTP 401。 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
