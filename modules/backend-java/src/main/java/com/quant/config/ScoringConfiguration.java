package com.quant.config;

import com.quant.service.scoring.ScoringPropertiesBridge;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 打分相关 Bean 装配。
 *
 * <p>此前此处标注 {@code @EnableScheduling}，但全仓没有任何 {@code @Scheduled} 方法，
 * 该注解只会在上下文里多挂一个 {@code ScheduledAnnotationBeanPostProcessor}
 * 与默认单线程调度器（并发 1），既无用也会让人误以为存在定时任务。异步任务走的是
 * {@code AsyncExecutorConfig} 的显式线程池，与本注解无关，故移除。
 * 若后续新增 {@code @Scheduled} 任务，请随该任务一并加回并按需配置线程池大小。
 */
@Configuration
@EnableConfigurationProperties(ScoringProperties.class)
public class ScoringConfiguration {

    @Bean
    ScoringPropertiesBridge scoringPropertiesBridge(ScoringProperties properties) {
        return new ScoringPropertiesBridge(properties);
    }
}
