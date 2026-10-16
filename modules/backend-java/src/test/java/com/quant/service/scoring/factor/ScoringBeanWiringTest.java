package com.quant.service.scoring.factor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.controller.UnifiedScoringController;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockFinancialSnapshotRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.UnifiedScoringTaskRepository;
import com.quant.research.SecurityMasterHistoryService;
import com.quant.service.MultiDimScoringService;
import com.quant.service.OversoldReboundScoringService;
import com.quant.service.PointInTimeUniverseProvider;
import com.quant.service.StockSelectorService;
import com.quant.service.StockTrendScoringService;
import com.quant.service.scoring.ComprehensiveScoringService;
import com.quant.service.scoring.FinancialSnapshotLoader;
import com.quant.service.scoring.UnifiedScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 批次 C 装配验证：让 <b>Spring 自己</b>满足构造器，以证明新增依赖确实可被装配。
 *
 * <p><b>为什么需要这个测试</b>：批次 C 给 {@link UnifiedScoringService} 插入了第 6 个构造参数
 * {@link UnifiedFactorScoringService}，并让 {@link UnifiedScoringController} 依赖两个新服务。
 * 编译通过<b>不</b>能证明装配正确——构造参数的<b>顺序</b>错误、类型重复导致的歧义、
 * 参数名与 {@code @Qualifier} 不匹配，这些都只在容器刷新时才暴露。
 * 手工 {@code new} 的单元测试同样证明不了，因为它们绕过了 Spring。
 *
 * <p>本测试注册<b>真实的</b>被测组件（靠 {@code @Import}），仅把外部依赖（仓储、执行器、
 * 旧策略服务）替换为 mock，因此一旦下列任一情况发生就会失败：
 * <ul>
 *   <li>新构造参数缺少对应 bean（{@code NoSuchBeanDefinitionException}）；</li>
 *   <li>多个同类型 bean 造成歧义（{@code NoUniqueBeanDefinitionException}）；</li>
 *   <li>{@code @Qualifier} 与实际 bean 名不匹配；</li>
 *   <li>构造参数顺序/类型被改错（依赖图跑偏）。</li>
 * </ul>
 *
 * <p>本测试<b>不</b>启动完整应用（不连 MySQL、不跑 Flyway），因此可在单测中安全执行。
 */
class ScoringBeanWiringTest {

    /**
     * 被测组件用 {@code @Import} 引入，从而走 Spring 的真实构造器注入。
     *
     * <p>注意 {@link PointInTimeUniverseProvider} 与 {@link FinancialSnapshotLoader} 也用
     * {@code @RequiredArgsConstructor}（构造器由 Lombok 生成），所以它们必须同样靠 {@code @Import}
     * 交给 Spring —— 若在本类里手写 {@code new}，就绕过了 Lombok 生成的构造器，
     * 也就验证不到它们的装配。
     */
    @Configuration
    @Import({
            UnifiedFactorScoringService.class,
            UnifiedFactorComparisonService.class,
            StrategyCatalogService.class,
            UnifiedScoreReadService.class,
            UnifiedFactorMarketLoader.class,
            LegacyResultRowWriter.class,
            FactorCrossSectionWriter.class,
            UnifiedScoringService.class,
            UnifiedScoringController.class,
            PointInTimeUniverseProvider.class,
            FinancialSnapshotLoader.class,
    })
    static class WiringConfig {

        // ── 新引擎的三个真实依赖 ──────────────────────────────────────
        @Bean
        MarketDataRepository marketDataRepository() {
            return mock(MarketDataRepository.class);
        }

        @Bean
        SecurityMasterHistoryService securityMasterHistoryService() {
            return mock(SecurityMasterHistoryService.class);
        }

        @Bean
        StockInfoRepository stockInfoRepository() {
            return mock(StockInfoRepository.class);
        }

        @Bean
        StockFinancialSnapshotRepository stockFinancialSnapshotRepository() {
            return mock(StockFinancialSnapshotRepository.class);
        }

        // ── UnifiedScoringService 的其余依赖 ──────────────────────────
        @Bean
        StockTrendScoringService stockTrendScoringService() {
            return mock(StockTrendScoringService.class);
        }

        @Bean
        ComprehensiveScoringService comprehensiveScoringService() {
            return mock(ComprehensiveScoringService.class);
        }

        @Bean
        MultiDimScoringService multiDimScoringService() {
            return mock(MultiDimScoringService.class);
        }

        @Bean
        StockSelectorService stockSelectorService() {
            return mock(StockSelectorService.class);
        }

        @Bean
        OversoldReboundScoringService oversoldReboundScoringService() {
            return mock(OversoldReboundScoringService.class);
        }

        @Bean
        SelectorResultRepository selectorResultRepository() {
            return mock(SelectorResultRepository.class);
        }

        @Bean
        SelectorResultItemRepository selectorResultItemRepository() {
            return mock(SelectorResultItemRepository.class);
        }

        @Bean
        com.quant.repository.FactorCrossSectionScoreRepository factorCrossSectionScoreRepository() {
            return mock(com.quant.repository.FactorCrossSectionScoreRepository.class);
        }

        @Bean
        UnifiedScoringTaskRepository unifiedScoringTaskRepository() {
            return mock(UnifiedScoringTaskRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean("singleThreadExecutor")
        ThreadPoolTaskExecutor singleThreadExecutor() {
            return mock(ThreadPoolTaskExecutor.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }

    @Test
    void contextResolvesAndWiresUnifiedScoringGraphThroughRealConstructors() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(WiringConfig.class)) {

            // 三个新组件 + 改造过的分发服务都必须能被解析
            assertThat(ctx.getBean(UnifiedFactorScoringService.class)).isNotNull();
            assertThat(ctx.getBean(UnifiedFactorComparisonService.class)).isNotNull();
            assertThat(ctx.getBean(StrategyCatalogService.class)).isNotNull();
            assertThat(ctx.getBean(UnifiedScoreReadService.class)).isNotNull();
            assertThat(ctx.getBean(UnifiedScoringService.class)).isNotNull();
            assertThat(ctx.getBean(UnifiedScoringController.class)).isNotNull();

            // 控制器现在依赖 5 个服务；证明它拿到的目录服务与读服务就是容器里的单例
            UnifiedScoringController controller = ctx.getBean(UnifiedScoringController.class);
            assertThat(readField(controller, "strategyCatalogService"))
                    .isSameAs(ctx.getBean(StrategyCatalogService.class));
            assertThat(readField(controller, "unifiedScoreReadService"))
                    .isSameAs(ctx.getBean(UnifiedScoreReadService.class));;
        }
    }

    @Test
    void unifiedScoringServiceReceivesTheFactorEngineNotADifferentScoringService() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(WiringConfig.class)) {

            UnifiedScoringService unified = ctx.getBean(UnifiedScoringService.class);

            // 构造参数有 5 个同族的打分服务，顺序极易写错。
            // 用反射读出实际注入值，证明第 6 个参数确实拿到的是<b>因子引擎</b>，
            // 而不是被错位注入成 OversoldReboundScoringService 之类。
            Object injected = readField(unified, "unifiedFactorScoringService");
            assertThat(injected).isInstanceOf(UnifiedFactorScoringService.class);
            assertThat(injected).isSameAs(ctx.getBean(UnifiedFactorScoringService.class));

            // 相邻参数也要各自正确，排除"整体错位一位"
            assertThat(readField(unified, "oversoldReboundScoringService"))
                    .isInstanceOf(OversoldReboundScoringService.class);
            assertThat(readField(unified, "selectorResultRepository"))
                    .isInstanceOf(SelectorResultRepository.class);
        }
    }

    @Test
    void comparisonServiceGetsDistinctLegacyAndFactorServices() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(WiringConfig.class)) {

            UnifiedFactorComparisonService cmp = ctx.getBean(UnifiedFactorComparisonService.class);
            Object legacy = readField(cmp, "unifiedScoringService");
            Object factor = readField(cmp, "unifiedFactorScoringService");

            // 对账服务同时依赖"旧分发服务"和"新因子引擎"，两者不能是同一个对象，
            // 否则对账会退化成自己和自己比（恒等于 1，看似完美实则无意义）。
            assertThat(legacy).isInstanceOf(UnifiedScoringService.class);
            assertThat(factor).isInstanceOf(UnifiedFactorScoringService.class);
            assertThat(legacy).isNotSameAs(factor);
        }
    }

    private static Object readField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法读取字段 " + name, e);
            }
        }
        throw new IllegalStateException("字段不存在: " + name);
    }
}
