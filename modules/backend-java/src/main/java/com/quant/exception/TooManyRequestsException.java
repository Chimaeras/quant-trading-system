package com.quant.exception;

/** 并发上限已满 / 限流 → HTTP 429（V2-MP-030）。 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
