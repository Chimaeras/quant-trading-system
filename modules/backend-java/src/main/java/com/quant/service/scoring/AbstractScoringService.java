package com.quant.service.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import lombok.extern.slf4j.Slf4j;

/**
 * 批量打分通用执行框架：线程池创建、CompletionService 批处理、优雅关闭。
 */
@Slf4j
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
                    // 保留 fail-open（该股结果置 null 由调用方跳过），但必须把根因记入日志，
                    // 避免整批任务静默丢结果却无从排查（否则失败只表现为 null 而原因不可见）。
                    Throwable cause = e.getCause();
                    log.warn("并行打分任务异常，该任务结果置 null：{}",
                            cause != null ? cause.toString() : e.toString());
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
