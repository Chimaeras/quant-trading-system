package com.quant.service;

import com.quant.entity.DataUpdateTask;
import com.quant.exception.BusinessException;
import com.quant.entity.DataUpdateTaskItem;
import com.quant.repository.DataUpdateTaskItemRepository;
import com.quant.repository.DataUpdateTaskRepository;
import com.quant.repository.StockInfoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class HistoricalDataUpdateService {
    private static final String A_SHARE_UNIVERSE_RESOURCE = "universe/a_share_codes.txt";

    private final HistoricalDataService historicalDataService;
    private final DataUpdateTaskRepository dataUpdateTaskRepository;
    private final DataUpdateTaskItemRepository dataUpdateTaskItemRepository;
    private final StockInfoRepository stockInfoRepository;
    private final ThreadPoolTaskExecutor dataUpdateTaskExecutor;
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();
    private final Set<String> cancelRequestedTaskIds = ConcurrentHashMap.newKeySet();
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCESS", "PARTIAL", "FAILED", "STOPPED");

    public HistoricalDataUpdateService(
            HistoricalDataService historicalDataService,
            DataUpdateTaskRepository dataUpdateTaskRepository,
            DataUpdateTaskItemRepository dataUpdateTaskItemRepository,
            StockInfoRepository stockInfoRepository,
            @Qualifier("dataUpdateExecutor") ThreadPoolTaskExecutor dataUpdateTaskExecutor) {
        this.historicalDataService = historicalDataService;
        this.dataUpdateTaskRepository = dataUpdateTaskRepository;
        this.dataUpdateTaskItemRepository = dataUpdateTaskItemRepository;
        this.stockInfoRepository = stockInfoRepository;
        this.dataUpdateTaskExecutor = dataUpdateTaskExecutor;
    }

    /**
     * 应用启动恢复：将上次崩溃遗留的非终态任务（PENDING/RUNNING/STOPPING）标记为 FAILED。
     * 这些任务已无对应 worker 附着（内存 taskFutures 在重启后为空），若不清理会永久卡在非终态，
     * 前端仍显示"可停止"但 stopTask 找不到 future。标记 FAILED 后用户可走 retryFailed 重放。
     * dataUpdateTaskItems 的逐标的进度不动——部分成功的明细仍可追溯。
     */
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.transaction.annotation.Transactional
    public void recoverOnStartup() {
        try {
            int orphaned = dataUpdateTaskRepository.markOrphanedTasksFailed();
            // 内存追踪表重启后本就为空，无需清理；仅清理可能残留的 cancel 标记
            cancelRequestedTaskIds.clear();
            if (orphaned > 0) {
                log.warn("历史数据更新任务启动恢复: {} 个孤儿任务（PENDING/RUNNING/STOPPING）已标记为 FAILED，可走重试重放", orphaned);
            }
        } catch (Exception e) {
            log.warn("历史数据更新任务启动恢复失败（DB 可能未就绪）: {}", e.getMessage());
        }
    }

    public Map<String, Object> createTask(Map<String, Object> request) {
        String mode = normalizeMode(String.valueOf(request.getOrDefault("mode", "SINGLE")));
        LocalDate endDate = parseDate(request.get("endDate"), LocalDate.now());
        LocalDate startDate = parseDate(request.get("startDate"), endDate.minusYears(1));
        if (startDate.isAfter(endDate)) {
            LocalDate t = startDate;
            startDate = endDate;
            endDate = t;
        }

        List<StockTarget> targets = resolveTargets(mode, request);
        if (targets.isEmpty()) {
            return Map.of("task_id", "", "status", "FAILED", "message", "未找到可更新股票");
        }
        int maxStocks = parseIntMax(request.get("maxStocks"), 0, 0, 200_000);
        if (maxStocks > 0 && targets.size() > maxStocks) {
            targets = new ArrayList<>(targets.subList(0, maxStocks));
        }
        final List<StockTarget> targetsForRun = targets;

        long delayMs = resolveDelayMs(request.get("delayMs"), mode);

        DataUpdateTask task = new DataUpdateTask();
        task.setTaskId(genTaskId());
        task.setMode(mode);
        task.setStatus("PENDING");
        task.setStartDate(startDate);
        task.setEndDate(endDate);
        task.setStockList(buildStockListText(mode, targets));
        task.setTotalStocks(targets.size());
        dataUpdateTaskRepository.save(task);

        final LocalDate s = startDate;
        final LocalDate e = endDate;
        final long delayBetween = delayMs;
        final boolean stockInfoStyleMainBoard = "MAIN_BOARD".equals(mode);
        try {
            // 先登记后提交：先 submit 后 put 时任务可能在 put 前跑完，worker 的 finally
            // remove 落空 → 已完成 Future 永久残留（R31-P3-1）。
            FutureTask<Void> future = new FutureTask<Void>(() -> {
                runTask(task.getTaskId(), targetsForRun, s, e, delayBetween, stockInfoStyleMainBoard);
                return null;
            });
            taskFutures.put(task.getTaskId(), future);
            dataUpdateTaskExecutor.execute(future);
        } catch (RejectedExecutionException ex) {
            // executor 饱和或关闭时提交抛异常，须将 PENDING 任务标记为 FAILED 避免孤儿
            taskFutures.remove(task.getTaskId());
            task.setStatus("FAILED");
            task.setMessage("executor rejected task: " + ex.getMessage());
            dataUpdateTaskRepository.save(task);
            log.error("task {} rejected by executor", task.getTaskId(), ex);
            throw new BusinessException("任务提交失败（执行器饱和）: " + ex.getMessage());
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("task_id", task.getTaskId());
        res.put("status", task.getStatus());
        res.put("total_stocks", task.getTotalStocks());
        res.put("delay_ms", delayMs);
        res.put("mode", mode);
        return res;
    }

    public Map<String, Object> getTaskDetail(String taskId, int page, int size) {
        DataUpdateTask task = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) return null;
        int p = Math.max(1, page);
        int s = Math.min(500, Math.max(1, size));
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<DataUpdateTaskItem> items = dataUpdateTaskItemRepository.findByTaskId(taskId, pageable);
        List<String> keys = items.getContent().stream()
                .map(i -> i.getStockCode() + "|" + i.getExchangeType())
                .distinct()
                .toList();
        Map<String, String> stockNames = new HashMap<>();
        if (!keys.isEmpty()) {
            stockNames.putAll(stockInfoRepository.findByStockKeyIn(keys).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            s0 -> s0.getStockCode() + "|" + s0.getExchangeType(),
                            s0 -> s0.getStockName() == null ? "" : s0.getStockName(),
                            (a, b) -> a)));
        }
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (DataUpdateTaskItem i : items.getContent()) {
            itemMaps.add(Map.of(
                    "stock_code", i.getStockCode(),
                    "stock_name", stockNames.getOrDefault(i.getStockCode() + "|" + i.getExchangeType(), ""),
                    "exchange_type", i.getExchangeType(),
                    "status", i.getStatus(),
                    "inserted", i.getInsertedCount(),
                    "skipped", i.getSkippedCount(),
                    "fetched", i.getFetchedCount(),
                    "message", i.getMessage() != null ? i.getMessage() : ""
            ));
        }
        Map<String, Object> out = toTaskMap(task);
        out.put("items", itemMaps);
        out.put("items_page", p);
        out.put("items_size", s);
        out.put("items_total", items.getTotalElements());
        out.put("items_total_pages", items.getTotalPages());
        out.put("items_order", "id_desc");
        return out;
    }

    public Map<String, Object> listTasks(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        var pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "createdAt"));
        var data = dataUpdateTaskRepository.findAll(pageable);
        List<Map<String, Object>> items = data.getContent().stream().map(this::toTaskMap).toList();
        return Map.of("items", items, "page", p, "size", s, "total", data.getTotalElements());
    }

    public Map<String, Object> retryFailed(String taskId) {
        DataUpdateTask oldTask = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
        if (oldTask == null) return null;
        List<DataUpdateTaskItem> failed = dataUpdateTaskItemRepository.findByTaskIdAndStatus(taskId, "FAILED");
        if (failed.isEmpty()) return Map.of("task_id", taskId, "status", "NOOP", "message", "没有失败项可重试");

        List<StockTarget> targets = failed.stream()
                .map(i -> new StockTarget(i.getStockCode(), i.getExchangeType()))
                .toList();
        DataUpdateTask retry = new DataUpdateTask();
        retry.setTaskId(genTaskId());
        retry.setMode("RETRY");
        retry.setStatus("PENDING");
        retry.setStartDate(oldTask.getStartDate());
        retry.setEndDate(oldTask.getEndDate());
        retry.setStockList(targetsToText(targets));
        retry.setTotalStocks(targets.size());
        dataUpdateTaskRepository.save(retry);

        try {
            FutureTask<Void> future = new FutureTask<Void>(() -> {
                runTask(retry.getTaskId(), targets, retry.getStartDate(), retry.getEndDate(), 150L, false);
                return null;
            });
            taskFutures.put(retry.getTaskId(), future);
            dataUpdateTaskExecutor.execute(future);
        } catch (RejectedExecutionException ex) {
            taskFutures.remove(retry.getTaskId());
            retry.setStatus("FAILED");
            retry.setMessage("executor rejected task: " + ex.getMessage());
            dataUpdateTaskRepository.save(retry);
            log.error("retry task {} rejected by executor", retry.getTaskId(), ex);
            throw new BusinessException("重试任务提交失败（执行器饱和）: " + ex.getMessage());
        }
        return Map.of("task_id", retry.getTaskId(), "status", retry.getStatus(), "total_stocks", retry.getTotalStocks());
    }

    public Map<String, Object> stopTask(String taskId) {
        DataUpdateTask task = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) return null;

        String status = String.valueOf(task.getStatus()).toUpperCase(Locale.ROOT);
        if (TERMINAL_STATUSES.contains(status)) {
            return Map.of(
                    "task_id", taskId,
                    "status", task.getStatus(),
                    "message", "任务已结束，无需停止"
            );
        }

        cancelRequestedTaskIds.add(taskId);
        task.setStatus("STOPPING");
        task.setMessage("收到停止请求，正在终止");
        dataUpdateTaskRepository.save(task);

        Future<?> future = taskFutures.get(taskId);
        boolean cancelled = future != null && future.cancel(true);

        if ("PENDING".equals(status) && (future == null || cancelled || future.isCancelled())) {
            task.setStatus("STOPPED");
            task.setFinishedAt(LocalDateTime.now());
            task.setMessage(String.format("已手动停止，处理%d只，成功%d，失败%d",
                    safeInt(task.getProcessedStocks()), safeInt(task.getSuccessStocks()), safeInt(task.getFailedStocks())));
            dataUpdateTaskRepository.save(task);
            taskFutures.remove(taskId);
            cancelRequestedTaskIds.remove(taskId);
        }

        return Map.of(
                "task_id", taskId,
                "status", task.getStatus(),
                "message", task.getMessage() != null ? task.getMessage() : "",
                "cancel_signal_sent", cancelled
        );
    }

    private void runTask(String taskId, List<StockTarget> targets, LocalDate startDate, LocalDate endDate,
                         long delayMsBetweenStocks, boolean stockInfoStyleMainBoard) {
        try {
            DataUpdateTask task = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
            if (task == null) return;
            if (shouldStop(taskId)) {
                markTaskStopped(task, task.getProcessedStocks(), task.getSuccessStocks(), task.getFailedStocks());
                return;
            }
            task.setStatus("RUNNING");
            task.setStartedAt(LocalDateTime.now());
            dataUpdateTaskRepository.save(task);

            int processed = 0, success = 0, failed = 0;
            int inserted = 0, skipped = 0, fetched = 0;
            boolean stopped = false;
            for (int i = 0; i < targets.size(); i++) {
                if (shouldStop(taskId)) {
                    stopped = true;
                    break;
                }

                StockTarget t = targets.get(i);
                try {
                    Map<String, Object> res = stockInfoStyleMainBoard
                            ? historicalDataService.updateHistoryInRangeStockInfoStyle(t.stockCode(), t.exchangeType(), startDate, endDate)
                            : historicalDataService.updateHistoryInRange(t.stockCode(), t.exchangeType(), startDate, endDate);
                    int ins = toInt(res.get("inserted"));
                    int skp = toInt(res.get("skipped"));
                    int fch = toInt(res.get("total"));
                    inserted += ins;
                    skipped += skp;
                    fetched += fch;
                    success++;
                    saveItem(taskId, t, "SUCCESS", ins, skp, fch, String.valueOf(res.getOrDefault("message", "")));
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || shouldStop(taskId)) {
                        stopped = true;
                        break;
                    }
                    failed++;
                    saveItem(taskId, t, "FAILED", 0, 0, 0, e.getMessage());
                    log.warn("更新失败 {}.{}: {}", t.stockCode(), t.exchangeType(), e.getMessage());
                }
                processed++;
                task.setProcessedStocks(processed);
                task.setSuccessStocks(success);
                task.setFailedStocks(failed);
                task.setInsertedCount(inserted);
                task.setSkippedCount(skipped);
                task.setFetchedCount(fetched);
                dataUpdateTaskRepository.save(task);
                if (i < targets.size() - 1 && delayMsBetweenStocks > 0) {
                    try {
                        Thread.sleep(delayMsBetweenStocks);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        stopped = true;
                        break;
                    }
                }
            }
            task.setFinishedAt(LocalDateTime.now());
            if (stopped) {
                markTaskStopped(task, processed, success, failed);
            } else {
                task.setStatus(failed == 0 ? "SUCCESS" : (success > 0 ? "PARTIAL" : "FAILED"));
                task.setMessage(String.format("处理%d只，成功%d，失败%d", processed, success, failed));
                dataUpdateTaskRepository.save(task);
            }
        } catch (Exception e) {
            // 兜底：runTask 跑在 worker 线程里，异常一旦逃逸（例如任务行读取失败、saveItem
            // 自身抛错、targets 为 null），任务会永远停在 RUNNING 且没有 finishedAt，
            // 前端按状态轮询就再也不会结束。这里把它落成 FAILED 并带上原因。
            log.error("历史数据更新任务异常终止 taskId={}", taskId, e);
            try {
                dataUpdateTaskRepository.findByTaskId(taskId).ifPresent(t -> {
                    if (!"STOPPED".equalsIgnoreCase(t.getStatus())) {
                        t.setStatus("FAILED");
                        t.setFinishedAt(LocalDateTime.now());
                        t.setMessage("任务异常终止: "
                                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                        dataUpdateTaskRepository.save(t);
                    }
                });
            } catch (Exception inner) {
                // 兜底再失败就只能记日志，不能让它盖掉原始异常。
                log.error("标记历史数据更新任务失败状态时再次异常 taskId={}", taskId, inner);
            }
        } finally {
            taskFutures.remove(taskId);
            cancelRequestedTaskIds.remove(taskId);
        }
    }

    private void markTaskStopped(DataUpdateTask task, Integer processed, Integer success, Integer failed) {
        task.setFinishedAt(LocalDateTime.now());
        task.setStatus("STOPPED");
        task.setMessage(String.format("已手动停止，处理%d只，成功%d，失败%d",
                safeInt(processed), safeInt(success), safeInt(failed)));
        dataUpdateTaskRepository.save(task);
    }

    private boolean shouldStop(String taskId) {
        if (cancelRequestedTaskIds.contains(taskId)) return true;
        DataUpdateTask latest = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
        if (latest == null) return true;
        String status = String.valueOf(latest.getStatus()).toUpperCase(Locale.ROOT);
        return "STOPPING".equals(status) || "STOPPED".equals(status);
    }

    private int safeInt(Integer n) {
        return n == null ? 0 : n;
    }

    private void saveItem(String taskId, StockTarget t, String status, int inserted, int skipped, int fetched, String message) {
        DataUpdateTaskItem item = new DataUpdateTaskItem();
        item.setTaskId(taskId);
        item.setStockCode(t.stockCode());
        item.setExchangeType(t.exchangeType());
        item.setStatus(status);
        item.setInsertedCount(inserted);
        item.setSkippedCount(skipped);
        item.setFetchedCount(fetched);
        item.setMessage(message);
        dataUpdateTaskItemRepository.save(item);
    }

    private List<StockTarget> resolveTargets(String mode, Map<String, Object> request) {
        if ("MAIN_BOARD".equals(mode)) {
            // 主板模式也强制走固定清单，不再按 000001~ 区间盲扫
            return loadUniverseTargetsFromResource();
        }
        if ("ALL".equals(mode)) {
            // ALL 模式仅按固定清单执行，清单外股票直接跳过
            return loadUniverseTargetsFromResource();
        }
        if ("LIST".equals(mode)) {
            // 反序列化后的元素类型不受控（{"stockList":[123]} 会得到 List<Integer>），
            // 直接强转成 List<String> 只会在遍历时抛 ClassCastException → 500。
            // 逐元素取字符串，非标量元素明确报 400。
            List<String> list = new ArrayList<>();
            Object rawList = request.get("stockList");
            if (rawList instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof String s) {
                        list.add(s);
                    } else if (item instanceof Number || item instanceof Boolean) {
                        list.add(String.valueOf(item));
                    } else if (item != null) {
                        throw new IllegalArgumentException(
                                "stockList 元素必须是字符串或数字，实际 " + item.getClass().getSimpleName());
                    }
                }
            } else if (rawList != null) {
                throw new IllegalArgumentException("stockList 必须是数组");
            }
            if (list.isEmpty()) {
                String stockListText = String.valueOf(request.getOrDefault("stockListText", ""));
                if (!stockListText.isBlank()) list = Arrays.stream(stockListText.split("[,\\n;\\s]+")).toList();
            }
            List<StockTarget> targets = new ArrayList<>();
            for (String s : list) {
                StockTarget t = parseStock(s);
                if (t != null) targets.add(t);
            }
            return dedupTargets(targets);
        }
        String stockCode = String.valueOf(request.getOrDefault("stockCode", "")).trim();
        String exchange = String.valueOf(request.getOrDefault("exchange", "")).trim();
        if (stockCode.isBlank()) return List.of();
        return List.of(parseStock(exchange.isBlank() ? stockCode : stockCode + "." + exchangeToSuffix(exchange)));
    }

    private List<StockTarget> dedupTargets(List<StockTarget> list) {
        Map<String, StockTarget> map = new LinkedHashMap<>();
        for (StockTarget t : list) {
            if (t == null) continue;
            map.put(t.stockCode() + "_" + t.exchangeType(), t);
        }
        return new ArrayList<>(map.values());
    }

    private String targetsToText(List<StockTarget> list) {
        StringBuilder sb = new StringBuilder();
        for (StockTarget t : list) {
            if (sb.length() > 0) sb.append(",");
            sb.append(t.stockCode()).append(".").append(exchangeToSuffix(t.exchangeType()));
        }
        return sb.toString();
    }

    /**
     * 避免 mode=ALL / 大 LIST 时 stock_list 超长导致 MySQL 截断异常。
     * - ALL：仅保存摘要（不保存全量代码列表）
     * - LIST：保存前若干条 + 总数提示（便于排查、仍可读）
     */
    private String buildStockListText(String mode, List<StockTarget> targets) {
        if ("MAIN_BOARD".equalsIgnoreCase(mode)) {
            return "MAIN_BOARD(" + targets.size() + ")";
        }
        if ("ALL".equalsIgnoreCase(mode)) {
            return "ALL(" + targets.size() + ")";
        }
        String full = targetsToText(targets);
        // 保守限制：兼容旧库里 stock_list 仍是 VARCHAR 的情况（先止血）
        int maxLen = 2000;
        if (full.length() <= maxLen) return full;
        return full.substring(0, maxLen) + "...(total=" + targets.size() + ")";
    }

    private StockTarget parseStock(String codeOrSymbol) {
        if (codeOrSymbol == null) return null;
        String raw = codeOrSymbol.trim().toUpperCase();
        if (raw.isBlank()) return null;
        String code = raw.split("\\.")[0];
        String ex = "SZSE";
        if (raw.endsWith(".SH")) ex = "SSE";
        else if (raw.endsWith(".BJ")) ex = "BSE";
        else if (code.startsWith("4") || code.startsWith("8") || code.startsWith("92")) ex = "BSE";
        else if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) ex = "SSE";
        return new StockTarget(code, ex);
    }

    private String normalizeMode(String mode) {
        String m = mode == null ? "SINGLE" : mode.trim().toUpperCase();
        return switch (m) {
            case "ALL", "LIST", "RETRY", "MAIN_BOARD" -> m;
            default -> "SINGLE";
        };
    }

    /** 未传 delayMs 时：MAIN_BOARD 默认 150ms（对齐 StockInfo），其余模式无间隔 */
    private static long resolveDelayMs(Object delayParam, String mode) {
        if (delayParam != null) {
            return parseLongBounds(delayParam, 0L, 0L, 60_000L);
        }
        return "MAIN_BOARD".equals(mode) ? 150L : 0L;
    }

    private static long parseLongBounds(Object o, long def, long min, long max) {
        if (o == null) return def;
        if (o instanceof Number n) {
            return Math.max(min, Math.min(max, n.longValue()));
        }
        try {
            long v = Long.parseLong(String.valueOf(o).trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseIntMax(Object o, int def, int min, int max) {
        if (o == null) return def;
        if (o instanceof Number n) {
            return Math.max(min, Math.min(max, n.intValue()));
        }
        try {
            int v = Integer.parseInt(String.valueOf(o).trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * ALL / MAIN_BOARD 模式读取项目内固定股票清单，避免按代码区间盲扫。
     * 文件格式：每行一个股票代码，支持 000001 / 000001.SZ / 600519.SH / 920001.BJ。
     */
    private List<StockTarget> loadUniverseTargetsFromResource() {
        InputStream in = getClass().getClassLoader().getResourceAsStream(A_SHARE_UNIVERSE_RESOURCE);
        if (in == null) {
            log.warn("固定全量清单不存在: {}", A_SHARE_UNIVERSE_RESOURCE);
            return List.of();
        }
        List<StockTarget> targets = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String raw = line.trim();
                if (raw.isEmpty() || raw.startsWith("#")) {
                    continue;
                }
                StockTarget t = parseStock(raw);
                if (t != null) {
                    targets.add(t);
                }
            }
        } catch (Exception e) {
            log.warn("读取固定全量清单失败: {}", e.getMessage());
            return List.of();
        }
        List<StockTarget> deduped = dedupTargets(targets);
        if (deduped.isEmpty()) {
            log.warn("固定全量清单为空: {}", A_SHARE_UNIVERSE_RESOURCE);
        }
        return deduped;
    }

    private String exchangeToSuffix(String ex) {
        return "SSE".equals(ex) ? "SH" : ("BSE".equals(ex) ? "BJ" : "SZ");
    }

    private LocalDate parseDate(Object v, LocalDate def) {
        if (v == null) return def;
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private String genTaskId() {
        return "DU" + System.currentTimeMillis() + (int) (Math.random() * 9000 + 1000);
    }

    private Map<String, Object> toTaskMap(DataUpdateTask t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", t.getTaskId());
        out.put("mode", t.getMode());
        out.put("status", t.getStatus());
        out.put("start_date", String.valueOf(t.getStartDate()));
        out.put("end_date", String.valueOf(t.getEndDate()));
        out.put("total_stocks", t.getTotalStocks());
        out.put("processed_stocks", t.getProcessedStocks());
        out.put("success_stocks", t.getSuccessStocks());
        out.put("failed_stocks", t.getFailedStocks());
        out.put("inserted_count", t.getInsertedCount());
        out.put("skipped_count", t.getSkippedCount());
        out.put("fetched_count", t.getFetchedCount());
        out.put("message", t.getMessage() != null ? t.getMessage() : "");
        out.put("created_at", t.getCreatedAt() != null ? t.getCreatedAt().toString() : "");
        out.put("started_at", t.getStartedAt() != null ? t.getStartedAt().toString() : "");
        out.put("finished_at", t.getFinishedAt() != null ? t.getFinishedAt().toString() : "");
        out.put("can_stop", "PENDING".equals(t.getStatus()) || "RUNNING".equals(t.getStatus()) || "STOPPING".equals(t.getStatus()));
        return out;
    }

    private record StockTarget(String stockCode, String exchangeType) {}
}
