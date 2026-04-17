package com.quant.service;

import com.quant.entity.DataUpdateTask;
import com.quant.entity.DataUpdateTaskItem;
import com.quant.repository.DataUpdateTaskItemRepository;
import com.quant.repository.DataUpdateTaskRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalDataUpdateService {
    private static final String A_SHARE_UNIVERSE_RESOURCE = "universe/a_share_codes.txt";

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
        executor.submit(() -> runTask(task.getTaskId(), targetsForRun, s, e, delayBetween, stockInfoStyleMainBoard));

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
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (DataUpdateTaskItem i : items.getContent()) {
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

        executor.submit(() -> runTask(retry.getTaskId(), targets, retry.getStartDate(), retry.getEndDate(), 150L, false));
        return Map.of("task_id", retry.getTaskId(), "status", retry.getStatus(), "total_stocks", retry.getTotalStocks());
    }

    private void runTask(String taskId, List<StockTarget> targets, LocalDate startDate, LocalDate endDate,
                         long delayMsBetweenStocks, boolean stockInfoStyleMainBoard) {
        DataUpdateTask task = dataUpdateTaskRepository.findByTaskId(taskId).orElse(null);
        if (task == null) return;
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        dataUpdateTaskRepository.save(task);

        int processed = 0, success = 0, failed = 0;
        int inserted = 0, skipped = 0, fetched = 0;
        for (int i = 0; i < targets.size(); i++) {
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
                    break;
                }
            }
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
        /** 与 StockInfo/quant-core {@code StringUtil.generateMainBoardCodes()} 一致：深市 000001–004999 + 沪市 600000–605999 */
        if ("MAIN_BOARD".equals(mode)) {
            return generateMainBoardTargets();
        }
        if ("ALL".equals(mode)) {
            List<StockTarget> fromFile = loadUniverseTargetsFromResource();
            if (!fromFile.isEmpty()) {
                return fromFile;
            }
            // 兜底：当资源文件不存在或为空时，仍兼容历史逻辑（按 stock_info 全量）
            return stockInfoRepository.findAll(Sort.by(Sort.Direction.ASC, "stockCode")).stream()
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
        else if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) ex = "SSE";
        else if (code.startsWith("8") || code.startsWith("4")) ex = "BSE";
        return new StockTarget(code, ex);
    }

    private String normalizeMode(String mode) {
        String m = mode == null ? "SINGLE" : mode.trim().toUpperCase();
        return switch (m) {
            case "ALL", "LIST", "RETRY", "MAIN_BOARD" -> m;
            default -> "SINGLE";
        };
    }

    /**
     * 主板代码枚举（quant-core 同源逻辑），写入 {@link com.quant.entity.MarketData}（stock_code + exchange_type + trade_date 等）。
     */
    /** 与 StockInfo {@code StringUtil.generateMainBoardCodes()} 逐字一致 */
    private static List<StockTarget> generateMainBoardTargets() {
        List<StockTarget> codes = new ArrayList<>(11_000);
        for (int i = 1; i <= 4999; i++) {
            String code = String.format("00%04d", i);
            codes.add(new StockTarget(code, inferExchangeFromCode(code)));
        }
        for (int i = 0; i <= 5999; i++) {
            String code = String.format("60%04d", i);
            codes.add(new StockTarget(code, "SSE"));
        }
        return codes;
    }

    private static String inferExchangeFromCode(String code) {
        if (code == null || code.length() < 4) return "SZSE";
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "SSE";
        if (code.startsWith("8") || code.startsWith("4")) return "BSE";
        return "SZSE";
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

    private String normalizeExchange(String ex) {
        if (ex == null) return "SZSE";
        String e = ex.toUpperCase();
        if ("SH".equals(e) || "SSE".equals(e)) return "SSE";
        if ("BJ".equals(e) || "BSE".equals(e)) return "BSE";
        return "SZSE";
    }

    /**
     * ALL 模式优先读取项目内固定股票清单，避免按代码区间盲扫。
     * 文件格式：每行一个股票代码，支持 000001 / 000001.SZ / 600519.SH / 920001.BJ。
     */
    private List<StockTarget> loadUniverseTargetsFromResource() {
        InputStream in = getClass().getClassLoader().getResourceAsStream(A_SHARE_UNIVERSE_RESOURCE);
        if (in == null) {
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
        return dedupTargets(targets);
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
