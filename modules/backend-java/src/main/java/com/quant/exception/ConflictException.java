package com.quant.exception;

/** 状态冲突 / 非法转换 → HTTP 409（V2-MP-008 任务状态机 CAS、V2-MP-018 晋级）。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
