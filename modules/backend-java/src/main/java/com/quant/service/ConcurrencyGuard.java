package com.quant.service;

import com.quant.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 高成本端点并发上限（V2-MP-030）。
 *
 * <p>用信号量（非 synchronized）限制同一类高成本操作的在途并发数，超出返回 429。
 * 注意：这是实例内上限；多实例部署需换用共享限流（Redis/DB），
 * 本实现用于当前单实例部署并遵循计划「禁止 synchronized 解决多实例并发」。
 */
@Component
public class ConcurrencyGuard {

    private final ConcurrentHashMap<String, Semaphore> guards = new ConcurrentHashMap<>();

    /** 进入由 name 标识的并发段；已在途数达到 permits 时抛 429。 */
    public void tryEnter(String name, int permits) {
        Semaphore sem = guards.computeIfAbsent(name, k -> new Semaphore(permits, true));
        if (!sem.tryAcquire()) {
            throw new TooManyRequestsException("并发上限(" + name + ")已满，请稍后重试");
        }
    }

    /** 释放之前 tryEnter 占用的许可（finally 中调用）。 */
    public void exit(String name) {
        Semaphore sem = guards.get(name);
        if (sem != null) {
            sem.release();
        }
    }
}
