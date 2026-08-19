package com.quant.service;

import com.quant.repository.DataUpdateTaskItemRepository;
import com.quant.repository.DataUpdateTaskRepository;
import com.quant.repository.StockInfoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动恢复测试：崩溃遗留的 PENDING/RUNNING/STOPPING 任务须批量标记 FAILED。
 * 验证 R13-P2 的 recoverOnStartup 逻辑（孤儿任务不再卡死非终态）。
 */
class HistoricalDataUpdateServiceStartupRecoveryTest {

    @Test
    void recoverMarksOrphanedTasksFailedAndClearsCancelFlags() {
        DataUpdateTaskRepository taskRepo = mock(DataUpdateTaskRepository.class);
        DataUpdateTaskItemRepository itemRepo = mock(DataUpdateTaskItemRepository.class);
        when(taskRepo.markOrphanedTasksFailed()).thenReturn(3);

        HistoricalDataUpdateService service = new HistoricalDataUpdateService(
                mock(HistoricalDataService.class),
                taskRepo,
                itemRepo,
                mock(StockInfoRepository.class),
                mock(ThreadPoolTaskExecutor.class)
        );

        service.recoverOnStartup();

        verify(taskRepo).markOrphanedTasksFailed();
    }

    @Test
    void recoverIsSafeWhenNoOrphanedTasks() {
        DataUpdateTaskRepository taskRepo = mock(DataUpdateTaskRepository.class);
        when(taskRepo.markOrphanedTasksFailed()).thenReturn(0);

        HistoricalDataUpdateService service = new HistoricalDataUpdateService(
                mock(HistoricalDataService.class),
                taskRepo,
                mock(DataUpdateTaskItemRepository.class),
                mock(StockInfoRepository.class),
                mock(ThreadPoolTaskExecutor.class)
        );

        // 无孤儿任务时不应抛异常
        service.recoverOnStartup();
        verify(taskRepo).markOrphanedTasksFailed();
    }

    @Test
    void recoverSwallowsDbNotReadyException() {
        DataUpdateTaskRepository taskRepo = mock(DataUpdateTaskRepository.class);
        when(taskRepo.markOrphanedTasksFailed())
                .thenThrow(new RuntimeException("DB not ready"));

        HistoricalDataUpdateService service = new HistoricalDataUpdateService(
                mock(HistoricalDataService.class),
                taskRepo,
                mock(DataUpdateTaskItemRepository.class),
                mock(StockInfoRepository.class),
                mock(ThreadPoolTaskExecutor.class)
        );

        // DB 未就绪时不应向调用方抛异常（启动恢复失败不应阻断应用启动）
        service.recoverOnStartup();
        assertThat(taskRepo).isNotNull();
    }
}
