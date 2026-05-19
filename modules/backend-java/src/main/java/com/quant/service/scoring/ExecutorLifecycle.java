package com.quant.service.scoring;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 线程池优雅关闭工具。
 */
public final class ExecutorLifecycle {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FORCE_WAIT = Duration.ofSeconds(5);

    private ExecutorLifecycle() {}

    public static void shutdownGracefully(ExecutorService pool) {
        shutdownGracefully(pool, DEFAULT_TIMEOUT);
    }

    public static void shutdownGracefully(ExecutorService pool, Duration timeout) {
        if (pool == null || pool.isShutdown()) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeout.toSeconds(), TimeUnit.SECONDS)) {
                pool.shutdownNow();
                pool.awaitTermination(FORCE_WAIT.toSeconds(), TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
