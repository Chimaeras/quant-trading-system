package com.quant.service.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.UnifiedScoringTask;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.UnifiedScoringTaskRepository;
import com.quant.service.MultiDimScoringService;
import com.quant.service.OversoldReboundScoringService;
import com.quant.service.StockSelectorService;
import com.quant.service.StockTrendScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动恢复测试：崩溃后 RUNNING 任务无 worker 附着，须标记 FAILED；
 * PENDING 任务须重新排队。验证 R14-P1 的 recoverOnStartup 逻辑。
 */
class UnifiedScoringServiceStartupRecoveryTest {

    @Test
    void recoverMarksRunningAsFailedAndRequeuesPending() {
        UnifiedScoringTaskRepository taskRepo = mock(UnifiedScoringTaskRepository.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

        UnifiedScoringTask runningTask = new UnifiedScoringTask();
        runningTask.setTaskId("run-1");
        runningTask.setStatus("RUNNING");
        runningTask.setRequestJson("{\"strategy\":\"TREND\"}");

        UnifiedScoringTask pendingTask = new UnifiedScoringTask();
        pendingTask.setTaskId("pend-1");
        pendingTask.setStatus("PENDING");
        pendingTask.setRequestJson("{\"strategy\":\"TREND\"}");

        when(taskRepo.findByStatusOrderByCreatedAtAsc("RUNNING")).thenReturn(List.of(runningTask));
        when(taskRepo.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(pendingTask));

        UnifiedScoringService service = new UnifiedScoringService(
                mock(StockTrendScoringService.class),
                mock(ComprehensiveScoringService.class),
                mock(MultiDimScoringService.class),
                mock(StockSelectorService.class),
                mock(OversoldReboundScoringService.class),
                mock(com.quant.service.scoring.factor.UnifiedFactorScoringService.class),
                mock(SelectorResultRepository.class),
                mock(SelectorResultItemRepository.class),
                taskRepo,
                new ObjectMapper(),
                executor,
                mock(PlatformTransactionManager.class)
        );

        service.recoverOnStartup();

        // RUNNING → FAILED（持久化）
        assertThat(runningTask.getStatus()).isEqualTo("FAILED");
        verify(taskRepo).save(runningTask);

        // PENDING → 重新排队（提交到 executor；R31-P3-1 后为「先登记 FutureTask 再 execute」）
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void recoverIsSafeWhenNoOrphanedTasks() {
        UnifiedScoringTaskRepository taskRepo = mock(UnifiedScoringTaskRepository.class);
        when(taskRepo.findByStatusOrderByCreatedAtAsc(any(String.class))).thenReturn(List.of());

        UnifiedScoringService service = new UnifiedScoringService(
                mock(StockTrendScoringService.class),
                mock(ComprehensiveScoringService.class),
                mock(MultiDimScoringService.class),
                mock(StockSelectorService.class),
                mock(OversoldReboundScoringService.class),
                mock(com.quant.service.scoring.factor.UnifiedFactorScoringService.class),
                mock(SelectorResultRepository.class),
                mock(SelectorResultItemRepository.class),
                taskRepo,
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class),
                mock(PlatformTransactionManager.class)
        );

        // 无孤儿任务时不应抛异常
        service.recoverOnStartup();
        verify(taskRepo).findByStatusOrderByCreatedAtAsc(eq("RUNNING"));
        verify(taskRepo).findByStatusOrderByCreatedAtAsc(eq("PENDING"));
    }
}
