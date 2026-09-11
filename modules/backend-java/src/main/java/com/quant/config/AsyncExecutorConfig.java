package com.quant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池统一配置
 *
 * 配置参数说明：
 * - corePoolSize: 核心线程数，默认为CPU核心数
 * - maxPoolSize: 最大线程数，默认为CPU核心数*2
 * - queueCapacity: 队列容量，超过容量后新任务将阻塞
 * - keepAliveSeconds: 非核心线程空闲存活时间
 *
 * 使用方式：
 * 在需要异步执行的方法上添加 @Async("scoringTaskExecutor") 注解
 * 或注入 Executor bean 使用 executor.submit()
 */
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    @Value("${async.executor.core-pool-size:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}")
    private int corePoolSize;

    @Value("${async.executor.max-pool-size:#{T(java.lang.Runtime).getRuntime().availableProcessors() * 2}}")
    private int maxPoolSize;

    @Value("${async.executor.queue-capacity:100}")
    private int queueCapacity;

    @Value("${async.executor.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Value("${async.executor.thread-name-prefix:quant-async-}")
    private String threadNamePrefix;

    /**
     * 打分任务专用线程池
     * 用于股票打分、选股、回测等计算密集型任务
     */
    @Bean(name = "scoringTaskExecutor")
    public ThreadPoolTaskExecutor scoringTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);

        // 拒绝策略：调用者所在线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300);

        executor.initialize();
        return executor;
    }

    /**
     * 数据更新专用线程池
     * 用于历史数据更新、数据同步等IO密集型任务
     */
    @Bean(name = "dataUpdateExecutor")
    public ThreadPoolTaskExecutor dataUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, corePoolSize / 2));
        executor.setMaxPoolSize(Math.max(4, maxPoolSize / 2));
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("quant-data-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(600);
        executor.initialize();
        return executor;
    }

    /**
     * 单线程执行器
     * 用于需要顺序执行的任务队列
     *
     * <p>拒绝策略必须是 AbortPolicy：CallerRunsPolicy 在队列打满时会让提交线程
     * （Tomcat 请求线程）内联跑完整个任务，既与 worker 并发、破坏"顺序执行"语义，
     * 又把长耗时计算绑在 HTTP 线程上直到结束。调用方（UnifiedScoringService）已在
     * 提交前做准入限流，队列不会被填满，此处只做最后一道 fail-fast。
     */
    @Bean(name = "singleThreadExecutor")
    public ThreadPoolTaskExecutor singleThreadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("quant-single-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300);
        executor.initialize();
        return executor;
    }
}
