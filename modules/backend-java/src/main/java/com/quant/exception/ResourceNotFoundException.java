package com.quant.exception;

/** 资源不存在 → HTTP 404（V2-MP-008/030：研究任务等资源不再用 200 包 code=404）。 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
