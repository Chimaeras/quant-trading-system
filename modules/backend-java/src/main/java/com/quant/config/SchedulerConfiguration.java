package com.quant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 定时任务装配（DS-R030）。
 *
 * <p>{@code @EnableScheduling} 此前被移除过，原因记录在 {@link ScoringConfiguration}：
 * 当时全仓没有任何 {@code @Scheduled} 方法，该注解只会多挂一个
 * {@code ScheduledAnnotationBeanPostProcessor} 与默认单线程调度器，属于「看起来有定时任务」
 * 的误导。其注释已明确写明「若后续新增 {@code @Scheduled} 任务，请随该任务一并加回并按需
 * 配置线程池大小」——本次新增 {@code T1CandidateScheduler}，据此在此独立装配，避免把
 * 「打分 Bean 装配」与「调度基础设施」混在一个类里。
 *
 * <p>线程池刻意设为 <b>1</b>：日频候选生成是重 IO + 写库任务，宁可排队也不并发跑，
 * 避免同一信号日被两个线程同时生成。队列满时由调用线程执行（不丢任务）。
 */
@Configuration
@EnableScheduling
public class SchedulerConfiguration {

    /** 交易日历口径统一走上海时区，禁止依赖服务器默认时区。 */
    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock marketClock() {
        return Clock.system(MARKET_ZONE);
    }

    @Bean
    @ConditionalOnProperty(name = "quant.t1.schedule.enabled", havingValue = "true")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("quant-sched-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(60);
        s.setRemoveOnCancelPolicy(true);
        return s;
    }
}
