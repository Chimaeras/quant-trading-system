package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.PortfolioBacktestRunRepository;
import com.quant.repository.PortfolioNavPointRepository;
import com.quant.repository.PortfolioOrderRepository;
import com.quant.repository.PortfolioPositionRepository;
import com.quant.repository.PredictionRunRepository;
import com.quant.repository.PredictionSnapshotItemRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动恢复测试：崩溃遗留的 RUNNING 行须批量标记 FAILED。
 * 验证 R14-P2 的 recoverOnStartup 逻辑（无 PENDING 中间态，仅处理 RUNNING 孤儿）。
 */
class PortfolioBacktestServiceStartupRecoveryTest {

    private PortfolioBacktestService newServiceWith(PortfolioBacktestRunRepository runRepo) {
        return new PortfolioBacktestService(
                runRepo,
                mock(PortfolioOrderRepository.class),
                mock(PortfolioNavPointRepository.class),
                mock(PortfolioPositionRepository.class),
                mock(SelectorResultRepository.class),
                mock(SelectorResultItemRepository.class),
                mock(PredictionRunRepository.class),
                mock(PredictionSnapshotItemRepository.class),
                mock(MarketDataRepository.class),
                mock(MarketPriceRawRepository.class),
                mock(TradingCalendarService.class),
                new ObjectMapper(),
                mock(ThreadPoolTaskExecutor.class)
        );
    }

    @Test
    void recoverMarksOrphanedRunsFailed() {
        PortfolioBacktestRunRepository runRepo = mock(PortfolioBacktestRunRepository.class);
        when(runRepo.markOrphanedRunsFailed(any(String.class), any())).thenReturn(2);

        PortfolioBacktestService service = newServiceWith(runRepo);
        service.recoverOnStartup();

        verify(runRepo).markOrphanedRunsFailed(
                eq("应用重启恢复：原 RUNNING 状态无 worker 附着，标记 FAILED，请重新提交"),
                any());
    }

    @Test
    void recoverIsSafeWhenNoOrphanedRuns() {
        PortfolioBacktestRunRepository runRepo = mock(PortfolioBacktestRunRepository.class);
        when(runRepo.markOrphanedRunsFailed(any(String.class), any())).thenReturn(0);

        PortfolioBacktestService service = newServiceWith(runRepo);
        service.recoverOnStartup();
        verify(runRepo).markOrphanedRunsFailed(any(String.class), any());
    }

    @Test
    void recoverSwallowsDbNotReadyException() {
        PortfolioBacktestRunRepository runRepo = mock(PortfolioBacktestRunRepository.class);
        when(runRepo.markOrphanedRunsFailed(any(String.class), any()))
                .thenThrow(new RuntimeException("DB not ready"));

        PortfolioBacktestService service = newServiceWith(runRepo);

        // DB 未就绪时不应向调用方抛异常（启动恢复失败不应阻断应用启动）
        service.recoverOnStartup();
        assertThat(runRepo).isNotNull();
    }
}
