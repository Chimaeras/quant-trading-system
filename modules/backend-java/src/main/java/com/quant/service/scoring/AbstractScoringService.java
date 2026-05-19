package com.quant.service.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 批量打分通用执行框架：线程池创建、CompletionService 批处理、优雅关闭。
 */
public abstract class AbstractScoringService {

    protected <T> List<T> runParallel(
            int taskCount,
            int threads,
            String threadNamePrefix,
            Callable<T> taskFactory
    ) throws InterruptedException {
        if (taskCount <= 0) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(threads, taskCount)),
                r -> {
                    Thread t = new Thread(r);
                    t.setName(threadNamePrefix + t.getId());
                    t.setDaemon(true);
                    return t;
                });
        CompletionService<T> cs = new ExecutorCompletionService<>(pool);
        try {
            for (int i = 0; i < taskCount; i++) {
                cs.submit(taskFactory);
            }
            List<T> results = new ArrayList<>(taskCount);
            for (int i = 0; i < taskCount; i++) {
                ScoringUtils.checkInterrupted();
                try {
                    results.add(cs.take().get());
                } catch (ExecutionException e) {
                    results.add(null);
                }
            }
            return results;
        } finally {
            ExecutorLifecycle.shutdownGracefully(pool);
        }
    }

    protected void checkInterrupted() {
        ScoringUtils.checkInterrupted();
    }
}
