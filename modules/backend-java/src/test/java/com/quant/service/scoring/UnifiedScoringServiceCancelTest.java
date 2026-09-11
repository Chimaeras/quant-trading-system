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
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R31 统一打分任务并发收口回归测试。
 *
 * <ul>
 *   <li>取消与完成并发时，成功路径不得用方法开头的旧快照把 CANCELLED 覆盖回 SUCCESS；</li>
 *   <li>任务在 Future 登记之前跑完时，不得残留已完成的 Future 登记。</li>
 * </ul>
 */
class UnifiedScoringServiceCancelTest {

    private UnifiedScoringTaskRepository taskRepo;
    private StockTrendScoringService trendService;
    private ThreadPoolTaskExecutor executor;

    private UnifiedScoringService newService() {
        taskRepo = mock(UnifiedScoringTaskRepository.class);
        trendService = mock(StockTrendScoringService.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        return new UnifiedScoringService(
                trendService,
                mock(ComprehensiveScoringService.class),
                mock(MultiDimScoringService.class),
                mock(StockSelectorService.class),
                mock(OversoldReboundScoringService.class),
                mock(SelectorResultRepository.class),
                mock(SelectorResultItemRepository.class),
                taskRepo,
                new ObjectMapper(),
                executor,
                mock(PlatformTransactionManager.class)
        );
    }

    private static UnifiedScoringTask task(String taskId, String status) {
        UnifiedScoringTask t = new UnifiedScoringTask();
        t.setTaskId(taskId);
        t.setStrategy("TREND");
        t.setStatus(status);
        t.setRequestJson("{\"strategy\":\"TREND\"}");
        return t;
    }

    private static Map<String, Object> trendRequest() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("strategy", "TREND");
        req.put("persist", false);
        return req;
    }

    private static void invokeRunTask(UnifiedScoringService service, String taskId, Map<String, Object> req)
            throws Exception {
        Method m = UnifiedScoringService.class.getDeclaredMethod("runTask", String.class, Map.class);
        m.setAccessible(true);
        m.invoke(service, taskId, req);
    }

    private static Throwable invokeScheduleTask(UnifiedScoringService service, String taskId, Map<String, Object> req)
            throws Exception {
        Method m = UnifiedScoringService.class.getDeclaredMethod("scheduleTask", String.class, Map.class);
        m.setAccessible(true);
        try {
            m.invoke(service, taskId, req);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> inFlight(UnifiedScoringService service) throws Exception {
        Field f = UnifiedScoringService.class.getDeclaredField("queuedOrRunningTaskIds");
        f.setAccessible(true);
        return (Set<String>) f.get(service);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Future<?>> futures(UnifiedScoringService service) throws Exception {
        Field f = UnifiedScoringService.class.getDeclaredField("taskFutures");
        f.setAccessible(true);
        return (Map<String, Future<?>>) f.get(service);
    }

    @Test
    void cancelInDbIsNotOverwrittenBySuccessWrite() throws Exception {
        UnifiedScoringService service = newService();
        AtomicInteger lookups = new AtomicInteger();
        // 第 1 次查库是 worker 读到的入口快照（RUNNING）；其后 cancelTaskInternal 已把库改为 CANCELLED
        when(taskRepo.findByTaskId("t-cancel")).thenAnswer(inv ->
                Optional.of(task("t-cancel", lookups.incrementAndGet() == 1 ? "RUNNING" : "CANCELLED")));
        when(trendService.run(any())).thenReturn(new LinkedHashMap<>(Map.of("items", List.of())));

        invokeRunTask(service, "t-cancel", trendRequest());

        verify(taskRepo, never()).save(argThat(t -> t != null && "SUCCESS".equalsIgnoreCase(String.valueOf(t.getStatus()))));
        assertThat(inFlight(service)).doesNotContain("t-cancel");
    }

    @Test
    void completedTaskLeavesNoFutureRegistration() throws Exception {
        UnifiedScoringService service = newService();
        // 内联执行：复现「任务在 scheduleTask 返回之前就跑完」的极端时序
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(taskRepo.findByTaskId("t-leak")).thenAnswer(inv -> Optional.of(task("t-leak", "RUNNING")));
        when(trendService.run(any())).thenReturn(new LinkedHashMap<>(Map.of("items", List.of())));

        invokeScheduleTask(service, "t-leak", trendRequest());

        assertThat(futures(service)).as("已完成任务的 Future 登记必须被清理").doesNotContainKey("t-leak");
        assertThat(inFlight(service)).doesNotContain("t-leak");
    }

    @Test
    void rejectedSubmissionRollsBackRegistration() throws Exception {
        UnifiedScoringService service = newService();
        doAnswer(inv -> {
            throw new TaskRejectedException("queue full");
        }).when(executor).execute(any(Runnable.class));
        when(taskRepo.findByTaskId("t-reject")).thenReturn(Optional.of(task("t-reject", "PENDING")));

        assertThat(invokeScheduleTask(service, "t-reject", trendRequest())).isInstanceOf(TaskRejectedException.class);

        assertThat(futures(service)).doesNotContainKey("t-reject");
        assertThat(inFlight(service)).doesNotContain("t-reject");
    }

    @Test
    void schedulerSubmitsAndTaskReachesTerminalState() throws Exception {
        UnifiedScoringService service = newService();
        when(taskRepo.findByTaskId("t-cap")).thenAnswer(inv -> Optional.of(task("t-cap", "RUNNING")));
        when(trendService.run(any())).thenReturn(new LinkedHashMap<>(Map.of("items", List.of())));

        invokeScheduleTask(service, "t-cap", trendRequest());

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();
        verify(taskRepo, atLeastOnce()).save(argThat(t -> t != null && "SUCCESS".equalsIgnoreCase(String.valueOf(t.getStatus()))));
    }
}
