package com.quant.service;

import com.quant.entity.DataUpdateTask;
import com.quant.entity.DataUpdateTaskItem;
import com.quant.entity.StockInfo;
import com.quant.repository.DataUpdateTaskItemRepository;
import com.quant.repository.DataUpdateTaskRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalDataUpdateService {
    private final HistoricalDataService historicalDataService;
    private final StockInfoRepository stockInfoRepository;
    private final DataUpdateTaskRepository dataUpdateTaskRepository;
    private final DataUpdateTaskItemRepository dataUpdateTaskItemRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        executor.submit(() -> runTask(task.getTaskId(), targets, s, e));

        return Map.of("task_id", task.getTaskId(), "status", task.getStatus(), "total_stocks", task.getTotalStocks());
    }

    public Map<String, Object> getTaskDetail(String taskId) {
        DataUpdateTask task = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) return null;
        List<DataUpdateTaskItem> items = dataUpdateTaskItemRepository.findByTaskIdOrderByIdAsc(taskId);
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (DataUpdateTaskItem i : items) {
            itemMaps.add(Map.of(
                    "stock_code", i.getStockCode(),
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

        executor.submit(() -> runTask(retry.getTaskId(), targets, retry.getStartDate(), retry.getEndDate()));
        return Map.of("task_id", retry.getTaskId(), "status", retry.getStatus(), "total_stocks", retry.getTotalStocks());
    }

    private void runTask(String taskId, List<StockTarget> targets, LocalDate startDate, LocalDate endDate) {
        DataUpdateTask task = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) return;
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        dataUpdateTaskRepository.save(task);

        int processed = 0, success = 0, failed = 0;
        int inserted = 0, skipped = 0, fetched = 0;
        for (StockTarget t : targets) {
            try {
                Map<String, Object> res = historicalDataService.updateHistoryInRange(t.stockCode(), t.exchangeType(), startDate, endDate);
                int ins = toInt(res.get("inserted"));
                int skp = toInt(res.get("skipped"));
                int fch = toInt(res.get("total"));
                inserted += ins;
                skipped += skp;
                fetched += fch;
                success++;
                saveItem(taskId, t, "SUCCESS", ins, skp, fch, String.valueOf(res.getOrDefault("message", "")));
            } catch (Exception e) {
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
        }
        task.setFinishedAt(LocalDateTime.now());
        task.setStatus(failed == 0 ? "SUCCESS" : (success > 0 ? "PARTIAL" : "FAILED"));
        task.setMessage(String.format("处理%d只，成功%d，失败%d", processed, success, failed));
        dataUpdateTaskRepository.save(task);
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
        if ("ALL".equals(mode)) {
            return stockInfoRepository.findAll().stream()
                    .map(s -> new StockTarget(s.getStockCode(), normalizeExchange(s.getExchangeType())))
                    .toList();
        }
        if ("LIST".equals(mode)) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) request.getOrDefault("stockList", List.of());
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
        else if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) ex = "SSE";
        else if (code.startsWith("8") || code.startsWith("4")) ex = "BSE";
        return new StockTarget(code, ex);
    }

    private String normalizeMode(String mode) {
        String m = mode == null ? "SINGLE" : mode.trim().toUpperCase();
        return switch (m) {
            case "ALL", "LIST", "RETRY" -> m;
            default -> "SINGLE";
        };
    }

    private String normalizeExchange(String ex) {
        if (ex == null) return "SZSE";
        String e = ex.toUpperCase();
        if ("SH".equals(e) || "SSE".equals(e)) return "SSE";
        if ("BJ".equals(e) || "BSE".equals(e)) return "BSE";
        return "SZSE";
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
        return out;
    }

    private record StockTarget(String stockCode, String exchangeType) {}
}
