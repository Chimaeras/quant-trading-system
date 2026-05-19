package com.quant.service.scoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.UnifiedScoringTaskRepository;
import com.quant.service.GeopolicyNewsScoringService;
import com.quant.service.MultiDimScoringService;
import com.quant.service.OversoldReboundScoringService;
import com.quant.service.StockSelectorService;
import com.quant.service.StockTrendScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;

/**
 * 统一打分调度入口：按策略路由到各子服务，再统一写入 selector_result/selector_result_item。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedScoringService {

    private static final int MAX_ITEMS = 500;
    private static final Set<String> RESULT_RESUMABLE_STATUSES = Set.of("RUNNING", "PARTIAL", "FAILED", "CANCELLED");

    private final StockTrendScoringService trendScoringService;
    private final ComprehensiveScoringService comprehensiveScoringService;
    private final MultiDimScoringService multiDimScoringService;
    private final StockSelectorService stockSelectorService;
    private final OversoldReboundScoringService oversoldReboundScoringService;
    private final GeopolicyNewsScoringService geopolicyNewsScoringService;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final UnifiedScoringTaskRepository unifiedScoringTaskRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> queuedOrRunningTaskIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();

    @PreDestroy
    void shutdownTaskExecutor() {
        ExecutorLifecycle.shutdownGracefully(taskExecutor);
    }

    @Transactional
    public Map<String, Object> run(Map<String, Object> request) {
        Map<String, Object> req = request != null ? request : Map.of();
        UnifiedScoringStrategy strategy = UnifiedScoringStrategy.parse(String.valueOf(req.getOrDefault("strategy", "CLASSIC_SELECTOR")));
        int topN = parseInt(req.get("topN"), 100, 1, MAX_ITEMS);
        boolean persist = parseBool(req.get("persist"), true);

        Map<String, Object> strategyReq = new LinkedHashMap<>(req);
        strategyReq.remove("strategy");
        strategyReq.remove("topN");
        strategyReq.remove("persist");

        Map<String, Object> rawResult = dispatch(strategy, strategyReq);
        List<Map<String, Object>> rawItems = readItems(rawResult);
        List<Map<String, Object>> slicedItems = rawItems.stream().limit(topN).toList();
        String tradeDate = String.valueOf(rawResult.getOrDefault("trade_date", strategyReq.getOrDefault("tradeDate", "")));
        String factorVersion = String.valueOf(rawResult.getOrDefault("factor_version", strategy.name().toLowerCase()));

        String resultId = null;
        if (persist) {
            resultId = persistUnified(strategy, factorVersion, tradeDate, topN, strategyReq, rawResult, slicedItems);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result_id", resultId);
        out.put("strategy", strategy.name());
        out.put("factor_version", factorVersion);
        out.put("trade_date", tradeDate);
        out.put("persisted", persist);
        out.put("total_items", rawItems.size());
        out.put("returned_items", slicedItems.size());
        out.put("items", slicedItems);
        out.put("source_summary", extractSummary(rawResult));
        return out;
    }

    @Transactional
    public Map<String, Object> createTask(Map<String, Object> request) {
        Map<String, Object> req = request != null ? request : Map.of();
        UnifiedScoringStrategy strategy = UnifiedScoringStrategy.parse(String.valueOf(req.getOrDefault("strategy", "CLASSIC_SELECTOR")));
        Map<String, Object> normalizedReq = normalizeRequestForFingerprint(req, strategy);
        String requestFingerprint = buildFingerprint(strategy.name(), normalizedReq);
        String taskId = "USK" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);

        com.quant.entity.SelectorResult successfulResult = selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndRunStatusInOrderByCreatedAtDesc(
                        strategy.name(), requestFingerprint, List.of("SUCCESS")
                ).orElse(null);
        if (successfulResult != null) {
            long doneItems = selectorResultItemRepository.countByResultId(successfulResult.getResultId());
            if (doneItems > 0) {
                com.quant.entity.UnifiedScoringTask fastTask = new com.quant.entity.UnifiedScoringTask();
                fastTask.setTaskId(taskId);
                fastTask.setStrategy(strategy.name());
                fastTask.setStatus("SUCCESS");
                fastTask.setProgressPct(100);
                fastTask.setResultId(successfulResult.getResultId());
                fastTask.setReturnedItems((int) doneItems);
                fastTask.setTotalItems((int) doneItems);
                fastTask.setRequestFingerprint(requestFingerprint);
                fastTask.setReusedResult(true);
                fastTask.setResumeFromResultId(successfulResult.getResultId());
                fastTask.setStartedAt(LocalDateTime.now());
                fastTask.setFinishedAt(LocalDateTime.now());
                fastTask.setMessage("命中同参历史结果，直接复用");
                try {
                    fastTask.setRequestJson(objectMapper.writeValueAsString(normalizedReq));
                } catch (Exception e) {
                    fastTask.setRequestJson("{}");
                }
                unifiedScoringTaskRepository.save(fastTask);

                return new LinkedHashMap<>(Map.of(
                        "task_id", taskId,
                        "strategy", strategy.name(),
                        "status", "SUCCESS",
                        "progress_pct", 100,
                        "message", "命中同参历史结果，直接复用",
                        "reused_result", true,
                        "result_id", successfulResult.getResultId()
                ));
            }
        }

        com.quant.entity.SelectorResult resumableResult = selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndRunStatusInOrderByCreatedAtDesc(
                        strategy.name(), requestFingerprint, RESULT_RESUMABLE_STATUSES
                ).orElse(null);

        com.quant.entity.UnifiedScoringTask task = new com.quant.entity.UnifiedScoringTask();
        task.setTaskId(taskId);
        task.setStrategy(strategy.name());
        task.setStatus("PENDING");
        task.setProgressPct(0);
        task.setMessage("任务已创建，等待执行");
        task.setRequestFingerprint(requestFingerprint);
        if (resumableResult != null) {
            task.setResumeFromResultId(resumableResult.getResultId());
            task.setResultId(resumableResult.getResultId());
            task.setMessage("命中同参历史任务，准备断点续跑");
        }
        try {
            task.setRequestJson(objectMapper.writeValueAsString(normalizedReq));
        } catch (Exception e) {
            task.setRequestJson("{}");
        }
        unifiedScoringTaskRepository.save(task);

        scheduleTaskAfterCommit(taskId, normalizedReq);
        long runningCount = unifiedScoringTaskRepository.countByStatus("RUNNING");
        long pendingAhead = task.getCreatedAt() != null
                ? unifiedScoringTaskRepository.countByStatusAndCreatedAtBefore("PENDING", task.getCreatedAt())
                : 0L;
        String queueMessage = (runningCount > 0 || pendingAhead > 0)
                ? String.format("任务已排队：前方 %d 个等待任务，当前运行 %d 个", pendingAhead, runningCount)
                : "任务已创建，等待执行";
        task.setMessage(queueMessage);
        unifiedScoringTaskRepository.save(task);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", taskId);
        out.put("strategy", strategy.name());
        out.put("status", "PENDING");
        out.put("progress_pct", 0);
        out.put("message", queueMessage);
        out.put("pending_ahead", pendingAhead);
        out.put("running_count", runningCount);
        out.put("request_fingerprint", requestFingerprint);
        out.put("resume_result_id", resumableResult != null ? resumableResult.getResultId() : "");
        out.put("reused_result", false);
        return out;
    }

    @Transactional
    public Map<String, Object> getTask(String taskId) {
        com.quant.entity.UnifiedScoringTask task = unifiedScoringTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) {
            return null;
        }

        if ("PENDING".equalsIgnoreCase(task.getStatus())) {
            ensurePendingTaskScheduled(task);
            long runningCount = unifiedScoringTaskRepository.countByStatus("RUNNING");
            long pendingAhead = task.getCreatedAt() != null
                    ? unifiedScoringTaskRepository.countByStatusAndCreatedAtBefore("PENDING", task.getCreatedAt())
                    : 0L;
            if (pendingAhead > 0 || runningCount > 0) {
                task.setMessage(String.format("排队中：前方 %d 个等待任务，当前运行 %d 个", pendingAhead, runningCount));
                unifiedScoringTaskRepository.save(task);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", task.getTaskId());
        out.put("strategy", task.getStrategy());
        out.put("status", task.getStatus());
        out.put("progress_pct", task.getProgressPct());
        out.put("total_items", task.getTotalItems());
        out.put("returned_items", task.getReturnedItems());
        out.put("result_id", task.getResultId());
        out.put("message", nvl(task.getMessage(), ""));
        out.put("error_message", nvl(task.getErrorMessage(), ""));
        out.put("created_at", task.getCreatedAt() != null ? task.getCreatedAt().toString() : "");
        out.put("started_at", task.getStartedAt() != null ? task.getStartedAt().toString() : "");
        out.put("finished_at", task.getFinishedAt() != null ? task.getFinishedAt().toString() : "");
        out.put("updated_at", task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : "");
        out.put("pending_ahead", task.getCreatedAt() != null
                ? unifiedScoringTaskRepository.countByStatusAndCreatedAtBefore("PENDING", task.getCreatedAt())
                : 0L);
        out.put("running_count", unifiedScoringTaskRepository.countByStatus("RUNNING"));
        out.put("request_fingerprint", nvl(task.getRequestFingerprint(), ""));
        out.put("resume_from_result_id", nvl(task.getResumeFromResultId(), ""));
        out.put("reused_result", Boolean.TRUE.equals(task.getReusedResult()));
        if (task.getResultId() != null && !task.getResultId().isBlank()) {
            selectorResultRepository.findByResultId(task.getResultId()).ifPresent(r -> {
                out.put("processed_candidates", nvl(r.getProcessedCandidates(), 0));
                out.put("total_candidates", nvl(r.getTotalCandidates(), 0));
                out.put("success_candidates", nvl(r.getSuccessCandidates(), 0));
                out.put("failed_candidates", nvl(r.getFailedCandidates(), 0));
                out.put("result_run_status", nvl(r.getRunStatus(), ""));
            });
        }
        return out;
    }

    @Transactional
    public Map<String, Object> cancelTask(String taskId) {
        com.quant.entity.UnifiedScoringTask task = unifiedScoringTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) {
            return Map.of("task_id", taskId, "cancelled", false, "message", "任务不存在");
        }
        boolean changed = cancelTaskInternal(task, "用户手动取消任务");
        return Map.of(
                "task_id", taskId,
                "cancelled", changed,
                "status", task.getStatus(),
                "message", changed ? "任务已取消" : "任务已结束，无法取消"
        );
    }

    @Transactional
    public Map<String, Object> prioritizeTask(String taskId) {
        com.quant.entity.UnifiedScoringTask target = unifiedScoringTaskRepository.findByTaskId(taskId).orElse(null);
        if (target == null) {
            return Map.of("task_id", taskId, "ok", false, "message", "任务不存在");
        }
        if ("SUCCESS".equalsIgnoreCase(target.getStatus())
                || "FAILED".equalsIgnoreCase(target.getStatus())
                || "CANCELLED".equalsIgnoreCase(target.getStatus())) {
            return Map.of("task_id", taskId, "ok", false, "message", "任务已结束，无法抢占执行");
        }

        int cancelledPending = 0;
        int cancelledRunning = 0;

        List<com.quant.entity.UnifiedScoringTask> runningTasks = unifiedScoringTaskRepository.findByStatusOrderByCreatedAtAsc("RUNNING");
        for (com.quant.entity.UnifiedScoringTask t : runningTasks) {
            if (t == null || taskId.equals(t.getTaskId())) {
                continue;
            }
            if (cancelTaskInternal(t, "已被新任务抢占取消")) {
                cancelledRunning++;
            }
        }

        if (target.getCreatedAt() != null) {
            List<com.quant.entity.UnifiedScoringTask> pendingAhead =
                    unifiedScoringTaskRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc("PENDING", target.getCreatedAt());
            for (com.quant.entity.UnifiedScoringTask t : pendingAhead) {
                if (t == null || taskId.equals(t.getTaskId())) {
                    continue;
                }
                if (cancelTaskInternal(t, "已被新任务抢占取消")) {
                    cancelledPending++;
                }
            }
        }

        if ("PENDING".equalsIgnoreCase(target.getStatus()) || "RUNNING".equalsIgnoreCase(target.getStatus())) {
            Map<String, Object> req = readRequestJson(target.getRequestJson());
            scheduleTask(target.getTaskId(), req);
            target.setMessage("已抢占执行：前序任务已取消，当前任务优先运行");
            unifiedScoringTaskRepository.save(target);
        }

        return Map.of(
                "task_id", taskId,
                "ok", true,
                "cancelled_pending", cancelledPending,
                "cancelled_running", cancelledRunning,
                "message", String.format("已抢占执行：取消等待 %d 个，取消运行 %d 个", cancelledPending, cancelledRunning)
        );
    }

    @Transactional
    public Map<String, Object> clearPendingTasks() {
        List<com.quant.entity.UnifiedScoringTask> pendingTasks = unifiedScoringTaskRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        int cancelled = 0;
        for (com.quant.entity.UnifiedScoringTask t : pendingTasks) {
            if (cancelTaskInternal(t, "用户手动清空待执行队列")) {
                cancelled++;
            }
        }
        return Map.of(
                "ok", true,
                "cancelled_pending", cancelled,
                "message", String.format("已清空待执行队列，共取消 %d 个任务", cancelled)
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getLatest(String strategyRaw, String tradeDateRaw, int page, int size) {
        UnifiedScoringStrategy strategy = UnifiedScoringStrategy.parse(strategyRaw);
        int p = Math.max(1, page);
        int s = Math.min(500, Math.max(1, size));
        String strategyName = strategy.name();

        com.quant.entity.SelectorResult result;
        if (tradeDateRaw != null && !tradeDateRaw.isBlank()) {
            LocalDate d = parseDate(tradeDateRaw, null);
            if (d == null) {
                throw new IllegalArgumentException("tradeDate 格式错误，应为 yyyy-MM-dd");
            }
            result = selectorResultRepository.findFirstByTradeDateAndStrategyTypeOrderByCreatedAtDesc(d, strategyName).orElse(null);
        } else {
            LocalDate latest = selectorResultRepository.findMaxTradeDateByStrategy(strategyName).orElse(null);
            if (latest == null) {
                return emptyQuery(strategyName, p, s);
            }
            result = selectorResultRepository.findFirstByTradeDateAndStrategyTypeOrderByCreatedAtDesc(latest, strategyName).orElse(null);
        }
        if (result == null) {
            return emptyQuery(strategyName, p, s);
        }
        return getByResultId(result.getResultId(), p, s);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getByResultId(String resultId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(500, Math.max(1, size));
        com.quant.entity.SelectorResult result = selectorResultRepository.findByResultId(resultId).orElse(null);
        if (result == null) {
            return Map.of(
                    "result_id", resultId,
                    "list", List.of(),
                    "total", 0,
                    "page", p,
                    "size", s
            );
        }

        Pageable pageable = PageRequest.of(p - 1, s);
        Page<com.quant.entity.SelectorResultItem> pg = selectorResultItemRepository.findByResultIdOrderByRankNoAsc(result.getResultId(), pageable);

        List<Map<String, Object>> list = pg.getContent().stream()
                .map(this::toItemMap)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result_id", result.getResultId());
        out.put("strategy", nvl(result.getStrategyType(), ""));
        out.put("factor_version", nvl(result.getFactorVersion(), ""));
        out.put("trade_date", result.getTradeDate() != null ? result.getTradeDate().toString() : "");
        out.put("lookback_days", result.getLookbackDays());
        out.put("top_n", result.getTopN());
        out.put("min_avg_amount", result.getMinAvgAmount());
        out.put("min_trend_score", result.getMinTrendScore());
        out.put("template_id", result.getTemplateId());
        out.put("created_at", result.getCreatedAt() != null ? result.getCreatedAt().toString() : "");
        out.put("factor_snapshot", result.getFactorSnapshot() != null ? result.getFactorSnapshot() : Map.of());
        out.put("run_status", nvl(result.getRunStatus(), ""));
        out.put("request_fingerprint", nvl(result.getRequestFingerprint(), ""));
        out.put("processed_candidates", nvl(result.getProcessedCandidates(), 0));
        out.put("total_candidates", nvl(result.getTotalCandidates(), 0));
        out.put("success_candidates", nvl(result.getSuccessCandidates(), 0));
        out.put("failed_candidates", nvl(result.getFailedCandidates(), 0));
        out.put("last_processed_key", nvl(result.getLastProcessedKey(), ""));
        out.put("list", list);
        out.put("items", list);
        out.put("total", pg.getTotalElements());
        out.put("page", p);
        out.put("size", s);
        out.put("total_pages", pg.getTotalPages());
        return out;
    }

    private Map<String, Object> dispatch(UnifiedScoringStrategy strategy, Map<String, Object> req) {
        return switch (strategy) {
            case TREND -> runTrend(req);
            case COMPREHENSIVE -> runComprehensive(req);
            case MULTI_DIM -> runMultiDim(req);
            case CLASSIC_SELECTOR -> runClassicSelector(req);
            case OVERSOLD_REBOUND -> runOversold(req);
            case GEOPOLICY_NEWS -> runGeopolicy(req);
        };
    }

    private void runTask(String taskId, Map<String, Object> request) {
        com.quant.entity.UnifiedScoringTask task = unifiedScoringTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) {
            queuedOrRunningTaskIds.remove(taskId);
            taskFutures.remove(taskId);
            return;
        }
        try {
            queuedOrRunningTaskIds.add(taskId);
            if ("CANCELLED".equalsIgnoreCase(task.getStatus())) {
                return;
            }
            task.setStatus("RUNNING");
            task.setProgressPct(5);
            task.setStartedAt(LocalDateTime.now());
            task.setMessage("任务启动，准备参数");
            unifiedScoringTaskRepository.save(task);

            Map<String, Object> req = new LinkedHashMap<>(request != null ? request : Map.of());
            if (!req.containsKey("persist")) {
                req.put("persist", true);
            }
            boolean persist = parseBool(req.get("persist"), true);
            UnifiedScoringStrategy strategy = UnifiedScoringStrategy.parse(String.valueOf(req.getOrDefault("strategy", task.getStrategy())));
            String resumeResultId = task.getResumeFromResultId() != null ? task.getResumeFromResultId().trim() : "";
            if (persist) {
                String liveResultId = !resumeResultId.isBlank()
                        ? resumeResultId
                        : "USL" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
                req.put("resultId", liveResultId);
                if (supportsProgressive(strategy)) {
                    req.put("progressivePersist", true);
                }
                if (!resumeResultId.isBlank()) {
                    req.put("resumeMode", true);
                }
                task.setResultId(liveResultId);
                task.setMessage(!resumeResultId.isBlank()
                        ? "命中同参历史结果，正在断点续跑"
                        : "任务执行中，结果将持续写入，可实时查看已完成部分");
                unifiedScoringTaskRepository.save(task);
            }

            task.setProgressPct(15);
            task.setMessage("打分计算中（进度实时更新；经典/超跌结束后仅保留 TopN）");
            unifiedScoringTaskRepository.save(task);

            Map<String, Object> result = run(req);

            task.setProgressPct(90);
            task.setMessage("计算完成，整理结果中");
            task.setResultId(String.valueOf(result.getOrDefault("result_id", "")));
            task.setTotalItems(parseInt(result.get("total_items"), 0, 0, Integer.MAX_VALUE));
            task.setReturnedItems(parseInt(result.get("returned_items"), 0, 0, Integer.MAX_VALUE));
            if (task.getResumeFromResultId() != null && !task.getResumeFromResultId().isBlank()) {
                task.setMessage("断点续跑完成");
            }
            unifiedScoringTaskRepository.save(task);

            task.setStatus("SUCCESS");
            task.setProgressPct(100);
            task.setFinishedAt(LocalDateTime.now());
            task.setMessage("任务执行完成");
            unifiedScoringTaskRepository.save(task);
        } catch (Exception e) {
            com.quant.entity.UnifiedScoringTask latest = unifiedScoringTaskRepository.findByTaskId(taskId).orElse(task);
            boolean cancelled = "CANCELLED".equalsIgnoreCase(latest.getStatus())
                    || Thread.currentThread().isInterrupted()
                    || isCancellationException(e);
            if (cancelled) {
                latest.setStatus("CANCELLED");
                latest.setProgressPct(Math.max(1, nvl(latest.getProgressPct(), 0)));
                latest.setFinishedAt(LocalDateTime.now());
                latest.setMessage("任务已取消");
                unifiedScoringTaskRepository.save(latest);
                log.info("统一打分任务取消 taskId={} reason={}", taskId, e.getMessage());
            } else {
                latest.setStatus("FAILED");
                latest.setProgressPct(Math.max(1, nvl(latest.getProgressPct(), 0)));
                latest.setFinishedAt(LocalDateTime.now());
                latest.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                latest.setMessage("任务执行失败");
                unifiedScoringTaskRepository.save(latest);
                log.warn("统一打分任务失败 taskId={} err={}", taskId, e.getMessage());
            }
        } finally {
            queuedOrRunningTaskIds.remove(taskId);
            taskFutures.remove(taskId);
        }
    }

    private void scheduleTask(String taskId, Map<String, Object> request) {
        if (!queuedOrRunningTaskIds.add(taskId)) {
            return;
        }
        Future<?> future = taskExecutor.submit(() -> runTask(taskId, request));
        taskFutures.put(taskId, future);
    }

    private void scheduleTaskAfterCommit(String taskId, Map<String, Object> request) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            scheduleTask(taskId, request);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scheduleTask(taskId, request);
            }
        });
    }

    private void ensurePendingTaskScheduled(com.quant.entity.UnifiedScoringTask task) {
        if (task == null || !"PENDING".equalsIgnoreCase(task.getStatus())) {
            return;
        }
        if (queuedOrRunningTaskIds.contains(task.getTaskId())) {
            return;
        }
        if (unifiedScoringTaskRepository.countByStatus("RUNNING") > 0) {
            return;
        }
        Map<String, Object> req = readRequestJson(task.getRequestJson());
        scheduleTask(task.getTaskId(), req);
        task.setMessage("检测到任务未入队，已自动重新入队");
        unifiedScoringTaskRepository.save(task);
    }

    private boolean cancelTaskInternal(com.quant.entity.UnifiedScoringTask task, String reason) {
        if (task == null) {
            return false;
        }
        String status = String.valueOf(task.getStatus());
        if (!"PENDING".equalsIgnoreCase(status) && !"RUNNING".equalsIgnoreCase(status)) {
            return false;
        }

        task.setStatus("CANCELLED");
        task.setFinishedAt(LocalDateTime.now());
        task.setMessage(reason != null && !reason.isBlank() ? reason : "任务已取消");
        task.setErrorMessage("");
        unifiedScoringTaskRepository.save(task);

        queuedOrRunningTaskIds.remove(task.getTaskId());
        Future<?> future = taskFutures.remove(task.getTaskId());
        if (future != null) {
            future.cancel(true);
        }
        return true;
    }

    private static boolean isCancellationException(Exception e) {
        if (e == null) {
            return false;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("cancel") || msg.contains("interrupt") || msg.contains("中断") || msg.contains("取消");
    }

    private Map<String, Object> readRequestJson(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(requestJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignore) {
            return new HashMap<>();
        }
    }

    private static boolean supportsProgressive(UnifiedScoringStrategy strategy) {
        return strategy == UnifiedScoringStrategy.CLASSIC_SELECTOR
                || strategy == UnifiedScoringStrategy.OVERSOLD_REBOUND
                || strategy == UnifiedScoringStrategy.GEOPOLICY_NEWS;
    }

    private Map<String, Object> runTrend(Map<String, Object> req) {
        Map<String, Object> p = new LinkedHashMap<>(req);
        p.put("includeItems", true);
        p.put("persist", false);
        return trendScoringService.run(p);
    }

    private Map<String, Object> runComprehensive(Map<String, Object> req) {
        Map<String, Object> p = new LinkedHashMap<>(req);
        p.put("includeItems", true);
        p.put("persist", false);
        return comprehensiveScoringService.runBatch(p);
    }

    private Map<String, Object> runMultiDim(Map<String, Object> req) {
        Map<String, Object> p = new LinkedHashMap<>(req);
        p.put("includeItems", true);
        p.put("persist", false);
        p.put("bulkFetch", true);
        return multiDimScoringService.run(p);
    }

    private Map<String, Object> runClassicSelector(Map<String, Object> req) {
        Map<String, Object> p = new LinkedHashMap<>(req);
        p.put("factorVersion", "unified_classic_v1");
        return stockSelectorService.runSelection(p);
    }

    private Map<String, Object> runOversold(Map<String, Object> req) {
        Map<String, Object> p = new LinkedHashMap<>(req);
        p.put("persist", false);
        return oversoldReboundScoringService.run(p);
    }

    private Map<String, Object> runGeopolicy(Map<String, Object> req) {
        Map<String, Object> p = new LinkedHashMap<>(req);
        p.put("persist", false);
        return geopolicyNewsScoringService.run(p);
    }

    private String persistUnified(
            UnifiedScoringStrategy strategy,
            String factorVersion,
            String tradeDate,
            int topN,
            Map<String, Object> strategyReq,
            Map<String, Object> rawResult,
            List<Map<String, Object>> items) {

        String fixedResultId = String.valueOf(strategyReq.getOrDefault("resultId", "")).trim();
        String requestFingerprint = buildFingerprint(strategy.name(), normalizeRequestForFingerprint(strategyReq, strategy));
        String resultId = !fixedResultId.isBlank()
                ? fixedResultId
                : "US" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        LocalDate d = parseDate(tradeDate, LocalDate.now());

        if (!fixedResultId.isBlank()
                && (strategy == UnifiedScoringStrategy.CLASSIC_SELECTOR || strategy == UnifiedScoringStrategy.OVERSOLD_REBOUND)) {
            com.quant.entity.SelectorResult existing = selectorResultRepository.findByResultId(resultId).orElse(null);
            if (existing != null) {
                existing.setRequestFingerprint(requestFingerprint);
                Integer curLb = existing.getLookbackDays();
                int lb = parseInt(rawResult.get("lookback_days"), curLb != null ? curLb : 0, 0, 500_000);
                existing.setLookbackDays(lb);
                existing.setTopN(topN);
                Map<String, Object> snap = existing.getFactorSnapshot() != null
                        ? new LinkedHashMap<>(existing.getFactorSnapshot())
                        : new LinkedHashMap<>();
                snap.put("unified_layer", Map.of(
                        "strategy", strategy.name(),
                        "raw_summary", extractSummary(rawResult)
                ));
                existing.setFactorSnapshot(snap);
                existing.setRunStatus("SUCCESS");
                selectorResultRepository.save(existing);
                return resultId;
            }
        }

        com.quant.entity.SelectorResult sr = selectorResultRepository.findByResultId(resultId).orElse(new com.quant.entity.SelectorResult());
        sr.setResultId(resultId);
        sr.setTradeDate(d);
        sr.setLookbackDays(parseInt(strategyReq.get("lookbackDays"), 60, 1, 5000));
        sr.setTopN(topN);
        sr.setMinAvgAmount(parseDouble(strategyReq.get("minAvgAmount"), 0, 0, 1_000_000_000_000d));
        sr.setMinTrendScore(parseDouble(strategyReq.get("minTrendScore"), 0, 0, 100));
        sr.setTemplateId(String.valueOf(strategyReq.getOrDefault("templateId", "")).trim().isBlank()
                ? null : String.valueOf(strategyReq.get("templateId")).trim());
        sr.setFactorVersion(factorVersion);
        sr.setStrategyType(strategy.name());
        sr.setRequestFingerprint(requestFingerprint);
        sr.setRunStatus("RUNNING");
        sr.setTotalCandidates(Math.max(items.size(), parseInt(rawResult.get("scanned"), items.size(), 0, Integer.MAX_VALUE)));
        sr.setProcessedCandidates(0);
        sr.setSuccessCandidates(0);
        sr.setFailedCandidates(0);
        sr.setLastProcessedKey("");

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("entry", "unified_scoring");
        snapshot.put("strategy", strategy.name());
        snapshot.put("strategy_request", strategyReq);
        snapshot.put("raw_summary", extractSummary(rawResult));
        sr.setFactorSnapshot(snapshot);
        selectorResultRepository.save(sr);

        Set<String> existing = selectorResultItemRepository.findStockKeysByResultId(resultId);
        int rank = selectorResultItemRepository.findByResultIdOrderByRankNoAsc(resultId).size() + 1;
        int processed = 0;
        int success = 0;
        for (Map<String, Object> item : items) {
            String stockCode = str(item, "stock_code", "code");
            String exchangeType = str(item, "exchange_type", "exchange", "market");
            String stockKey = stockCode + "|" + exchangeType;
            processed++;
            if (existing.contains(stockKey)) {
                continue;
            }
            selectorResultItemRepository.save(toUnifiedItem(resultId, rank++, strategy, item));
            success++;
            sr.setProcessedCandidates(processed);
            sr.setSuccessCandidates(success);
            sr.setFailedCandidates(Math.max(0, processed - success));
            sr.setLastProcessedKey(stockKey);
            selectorResultRepository.save(sr);
        }
        sr.setProcessedCandidates(processed);
        sr.setSuccessCandidates(success);
        sr.setFailedCandidates(Math.max(0, processed - success));
        sr.setRunStatus("SUCCESS");
        selectorResultRepository.save(sr);
        return resultId;
    }

    private com.quant.entity.SelectorResultItem toUnifiedItem(
            String resultId,
            int rankNo,
            UnifiedScoringStrategy strategy,
            Map<String, Object> item) {
        com.quant.entity.SelectorResultItem e = new com.quant.entity.SelectorResultItem();
        e.setResultId(resultId);
        e.setRankNo(rankNo);
        e.setStrategyType(strategy.name());
        e.setStockCode(str(item, "stock_code", "code"));
        e.setExchangeType(str(item, "exchange_type", "exchange", "market"));
        e.setStockName(str(item, "stock_name", "name"));

        double finalScore = firstDouble(item, "final_score", "total_score", "composite_score", "score");
        e.setFinalScore(finalScore);
        e.setTrendScore(firstDouble(item, "trend_score", "score_trend", "score_technical", "score_trend_40"));
        e.setMomentumScore(firstDouble(item, "momentum_score", "score_bias", "score_fundamental", "oversold_score_30"));
        e.setVolumeScore(firstDouble(item, "volume_score", "score_volume", "score_sentiment", "volume_score_30"));
        e.setRiskPenalty(firstDouble(item, "risk_penalty"));
        e.setRet20(firstDouble(item, "ret20"));
        e.setRet60(firstDouble(item, "ret60"));
        e.setVolatility20(firstDouble(item, "volatility20", "weighted_score"));
        e.setMaxDrawdown60(firstDouble(item, "max_drawdown60"));
        e.setAvgAmount20(firstDouble(item, "avg_amount20"));

        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("strategy", strategy.name());
        reasons.put("normalized", Map.of(
                "final_score", e.getFinalScore(),
                "trend_score", e.getTrendScore(),
                "momentum_score", e.getMomentumScore(),
                "volume_score", e.getVolumeScore()
        ));
        reasons.put("raw_item", item);

        e.setReasons(reasons);
        e.setExtraMetrics(buildExtraMetrics(item));
        return e;
    }

    private Map<String, Object> buildExtraMetrics(Map<String, Object> item) {
        Map<String, Object> extra = new LinkedHashMap<>();
        putIfPresent(extra, "buy_signal", item.get("buy_signal"));
        putIfPresent(extra, "suggestion", item.get("suggestion"));
        putIfPresent(extra, "trend_status", item.get("trend_status"));
        putIfPresent(extra, "macd_status", item.get("macd_status"));
        putIfPresent(extra, "rsi_status", item.get("rsi_status"));
        putIfPresent(extra, "kdj_status", item.get("kdj_status"));
        putIfPresent(extra, "volume_status", item.get("volume_status"));
        putIfPresent(extra, "ma_status", item.get("ma_status"));
        putIfPresent(extra, "score_support", item.get("score_support"));
        putIfPresent(extra, "score_macd", item.get("score_macd"));
        putIfPresent(extra, "score_rsi", item.get("score_rsi"));
        if (item.get("reasons_json") instanceof String rj && !rj.isBlank()) {
            try {
                extra.put("reasons_json", objectMapper.readValue(rj, new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception ignored) {
                extra.put("reasons_json_raw", rj);
            }
        }
        return extra;
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private Map<String, Object> toItemMap(com.quant.entity.SelectorResultItem e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("rank_no", e.getRankNo());
        m.put("stock_code", nvl(e.getStockCode(), ""));
        m.put("exchange_type", nvl(e.getExchangeType(), ""));
        m.put("stock_name", nvl(e.getStockName(), ""));
        m.put("strategy", nvl(e.getStrategyType(), ""));
        m.put("final_score", nvl(e.getFinalScore(), 0d));
        m.put("trend_score", nvl(e.getTrendScore(), 0d));
        m.put("momentum_score", nvl(e.getMomentumScore(), 0d));
        m.put("volume_score", nvl(e.getVolumeScore(), 0d));
        m.put("risk_penalty", nvl(e.getRiskPenalty(), 0d));
        m.put("ret20", nvl(e.getRet20(), 0d));
        m.put("ret60", nvl(e.getRet60(), 0d));
        m.put("volatility20", nvl(e.getVolatility20(), 0d));
        m.put("max_drawdown60", nvl(e.getMaxDrawdown60(), 0d));
        m.put("avg_amount20", nvl(e.getAvgAmount20(), 0d));

        Map<String, Object> reasons = e.getReasons() != null ? e.getReasons() : Map.of();
        Map<String, Object> extra = e.getExtraMetrics() != null ? e.getExtraMetrics() : Map.of();
        m.put("reasons", reasons);
        m.put("extra_metrics", extra);

        // 兼容趋势/综合榜历史字段
        m.put("total_score", nvl(e.getFinalScore(), 0d));
        m.put("score_trend", nvl(e.getTrendScore(), 0d));
        m.put("score_bias", nvl(e.getMomentumScore(), 0d));
        m.put("score_volume", nvl(e.getVolumeScore(), 0d));
        m.put("score_technical", nvl(e.getTrendScore(), 0d));
        m.put("score_fundamental", nvl(e.getMomentumScore(), 0d));
        m.put("score_sentiment", nvl(e.getVolumeScore(), 0d));

        putIfPresent(m, "score_support", extra.get("score_support"));
        putIfPresent(m, "score_macd", extra.get("score_macd"));
        putIfPresent(m, "score_rsi", extra.get("score_rsi"));
        putIfPresent(m, "trend_status", extra.get("trend_status"));
        putIfPresent(m, "volume_status", extra.get("volume_status"));
        putIfPresent(m, "macd_status", extra.get("macd_status"));
        putIfPresent(m, "rsi_status", extra.get("rsi_status"));
        putIfPresent(m, "buy_signal", extra.get("buy_signal"));
        putIfPresent(m, "suggestion", extra.get("suggestion"));
        Object advice = extra.get("suggestion") != null ? extra.get("suggestion") : extra.get("buy_signal");
        putIfPresent(m, "investment_advice", advice);

        Object rawItem = reasons.get("raw_item");
        if (rawItem instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String k = String.valueOf(entry.getKey());
                if (!m.containsKey(k) || m.get(k) == null || Objects.equals(m.get(k), "")) {
                    m.put(k, entry.getValue());
                }
            }
        }
        return m;
    }

    private Map<String, Object> extractSummary(Map<String, Object> rawResult) {
        Map<String, Object> s = new LinkedHashMap<>();
        copyIfPresent(rawResult, s, "trade_date");
        copyIfPresent(rawResult, s, "factor_version");
        copyIfPresent(rawResult, s, "scored");
        copyIfPresent(rawResult, s, "scanned");
        copyIfPresent(rawResult, s, "skipped");
        copyIfPresent(rawResult, s, "elapsed_ms");
        copyIfPresent(rawResult, s, "persisted");
        copyIfPresent(rawResult, s, "risk_level");
        copyIfPresent(rawResult, s, "macro");
        return s;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key)) {
            dst.put(key, src.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readItems(Map<String, Object> result) {
        Object v = result.get("items");
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    private static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    private static double firstDouble(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v == null) {
                continue;
            }
            if (v instanceof Number n) {
                return round2(n.doubleValue());
            }
            try {
                return round2(Double.parseDouble(String.valueOf(v)));
            } catch (Exception ignored) {
            }
        }
        return 0.0;
    }

    private static double parseDouble(Object v, double def, double min, double max) {
        if (v == null) {
            return def;
        }
        try {
            double d = Double.parseDouble(String.valueOf(v));
            return Math.max(min, Math.min(max, d));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static int parseInt(Object v, int def, int min, int max) {
        if (v == null) {
            return def;
        }
        try {
            int d = Integer.parseInt(String.valueOf(v));
            return Math.max(min, Math.min(max, d));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static boolean parseBool(Object v, boolean def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isBlank()) {
            return def;
        }
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "y".equals(s);
    }

    private static LocalDate parseDate(String raw, LocalDate def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return LocalDate.parse(raw);
        } catch (Exception ignored) {
            return def;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static <T> T nvl(T v, T def) {
        return v != null ? v : def;
    }

    private String buildFingerprint(String strategy, Map<String, Object> normalizedReq) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("strategy", strategy);
            payload.put("request", normalizedReq);
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 32);
        } catch (Exception e) {
            return strategy + "_" + Math.abs(normalizedReq.hashCode());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeRequestForFingerprint(Map<String, Object> req, UnifiedScoringStrategy strategy) {
        Map<String, Object> copy = new LinkedHashMap<>(req != null ? req : Map.of());
        copy.remove("persist");
        copy.remove("resultId");
        copy.remove("progressivePersist");
        copy.remove("requestFingerprint");
        copy.put("strategy", strategy.name());
        return (Map<String, Object>) normalizeObject(copy);
    }

    @SuppressWarnings("unchecked")
    private Object normalizeObject(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) {
                if (k != null) keys.add(String.valueOf(k));
            }
            Collections.sort(keys);
            Map<String, Object> sorted = new LinkedHashMap<>();
            for (String k : keys) {
                sorted.put(k, normalizeObject(((Map<String, Object>) m).get(k)));
            }
            return sorted;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) out.add(normalizeObject(o));
            out.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
            return out;
        }
        String text = String.valueOf(value).trim();
        return text;
    }

    private Map<String, Object> emptyQuery(String strategyName, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strategyName);
        out.put("list", List.of());
        out.put("items", List.of());
        out.put("total", 0L);
        out.put("page", page);
        out.put("size", size);
        out.put("total_pages", 0);
        return out;
    }
}
