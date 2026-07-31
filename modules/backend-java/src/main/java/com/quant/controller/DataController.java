package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.entity.StockFinancialSnapshot;
import com.quant.entity.StockMarginDaily;
import com.quant.repository.StockFinancialSnapshotRepository;
import com.quant.repository.StockMarginDailyRepository;
import com.quant.service.DataService;
import com.quant.service.HistoricalDataService;
import com.quant.service.HistoricalDataUpdateService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final DataService dataService;
    private final HistoricalDataService historicalDataService;
    private final HistoricalDataUpdateService historicalDataUpdateService;
    private final StockFinancialSnapshotRepository stockFinancialSnapshotRepository;
    private final StockMarginDailyRepository stockMarginDailyRepository;
    private final com.quant.service.DataQualityService dataQualityService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(dataService.getOverview());
    }

    /** 数据质量驾驶舱：缺失交易日、list_date 缺失、更新滞后告警、复权口径披露。 */
    @GetMapping("/quality")
    public ApiResponse<Map<String, Object>> getQuality() {
        return ApiResponse.success(dataQualityService.buildQualityReport());
    }

    /** 最近 N 个交易日热门股票榜：成交额、换手率、量能放大、上涨天数综合打分。 */
    @GetMapping("/hot-stocks")
    public ApiResponse<Map<String, Object>> getHotStocks(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "20") int baselineDays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(dataService.getHotStocks(limit, days, baselineDays, endDate));
    }

    /** 用 K 线最早交易日回填 stock_info.list_date（仅补空值） */
    @PostMapping("/stock-info/backfill-list-date")
    public ApiResponse<Map<String, Object>> backfillListDate() {
        return ApiResponse.success(dataService.backfillListDateFromMarketData());
    }

    /**
     * QP-003c：回填不复权行情并推算复权因子（单只证券）。
     * 拉取东方财富不复权日 K（fqt=0）写入 market_price_raw，并推算 adjust_factor；
     * 已存在的 raw / factor 记录跳过（幂等）。双写并存，market_data 前复权保持不动。
     */
    @PostMapping("/backfill-raw")
    public ApiResponse<Map<String, Object>> backfillRaw(
            @RequestParam String stockCode,
            @RequestParam String exchange,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(
                historicalDataService.backfillRawAndDeriveAdjustFactors(stockCode, exchange, startDate, endDate));
    }

    /** 历史行情立即更新：按股票+日期范围拉取，仅补充缺失的交易日 */
    @PostMapping("/history/update")
    public ApiResponse<Map<String, Object>> updateHistory(
            @RequestParam String stockCode,
            @RequestParam(required = false) String exchange,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> result = historicalDataService.updateHistoryInRange(stockCode, exchange, startDate, endDate);
        return ApiResponse.success(result);
    }

    /** 任务化更新：SINGLE / LIST / ALL / MAIN_BOARD（主板代码枚举，与 StockInfo 同源） */
    @PostMapping("/update/run")
    public ApiResponse<Map<String, Object>> runUpdateTask(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> data = historicalDataUpdateService.createTask(request != null ? request : Map.of());
        return ApiResponse.success(data);
    }

    @GetMapping("/update/tasks")
    public ApiResponse<Map<String, Object>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(historicalDataUpdateService.listTasks(page, size));
    }

    @GetMapping("/update/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        Map<String, Object> data = historicalDataUpdateService.getTaskDetail(taskId, page, size);
        if (data == null) return ApiResponse.error(404, "任务不存在");
        return ApiResponse.success(data);
    }

    @PostMapping("/update/tasks/{taskId}/retry")
    public ApiResponse<Map<String, Object>> retryTask(@PathVariable String taskId) {
        Map<String, Object> data = historicalDataUpdateService.retryFailed(taskId);
        if (data == null) return ApiResponse.error(404, "任务不存在");
        return ApiResponse.success(data);
    }

    @PostMapping("/update/tasks/{taskId}/stop")
    public ApiResponse<Map<String, Object>> stopTask(@PathVariable String taskId) {
        Map<String, Object> data = historicalDataUpdateService.stopTask(taskId);
        if (data == null) return ApiResponse.error(404, "任务不存在");
        return ApiResponse.success(data);
    }

    @Data
    public static class FinancialItem {
        private String stockCode;
        private String exchangeType;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate reportPeriod;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate announcementDate;
        private BigDecimal revenueYoY;
        private BigDecimal netProfitYoY;
        private BigDecimal roe;
        private BigDecimal debtRatio;
        private BigDecimal pbRatio;
        private BigDecimal currentRatio;
    }

    @Data
    public static class MarginItem {
        private String stockCode;
        private String exchangeType;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate tradeDate;
        private BigDecimal marginBalance;
    }

    /** 财报指标批量导入（akshare 脚本等） */
    @PostMapping("/financial/bulk")
    @Transactional
    public ApiResponse<Map<String, Object>> bulkFinancial(@RequestBody List<FinancialItem> items) {
        if (items == null || items.isEmpty()) {
            return ApiResponse.success(Map.of("saved", 0));
        }
        List<StockFinancialSnapshot> rows = new ArrayList<>();
        for (FinancialItem it : items) {
            if (it.getStockCode() == null || it.getExchangeType() == null || it.getReportPeriod() == null) {
                continue;
            }
            StockFinancialSnapshot f = new StockFinancialSnapshot();
            f.setStockCode(it.getStockCode().trim());
            f.setExchangeType(it.getExchangeType().trim());
            f.setReportPeriod(it.getReportPeriod());
            f.setAnnouncementDate(it.getAnnouncementDate());
            f.setRevenueYoY(it.getRevenueYoY());
            f.setNetProfitYoY(it.getNetProfitYoY());
            f.setRoe(it.getRoe());
            f.setDebtRatio(it.getDebtRatio());
            f.setPbRatio(it.getPbRatio());
            f.setCurrentRatio(it.getCurrentRatio());
            rows.add(f);
        }
        stockFinancialSnapshotRepository.saveAll(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", rows.size());
        return ApiResponse.success(out);
    }

    /** 融资余额批量导入 */
    @PostMapping("/margin/bulk")
    @Transactional
    public ApiResponse<Map<String, Object>> bulkMargin(@RequestBody List<MarginItem> items) {
        if (items == null || items.isEmpty()) {
            return ApiResponse.success(Map.of("saved", 0));
        }
        List<StockMarginDaily> rows = new ArrayList<>();
        for (MarginItem it : items) {
            if (it.getStockCode() == null || it.getExchangeType() == null || it.getTradeDate() == null) {
                continue;
            }
            StockMarginDaily m = new StockMarginDaily();
            m.setStockCode(it.getStockCode().trim());
            m.setExchangeType(it.getExchangeType().trim());
            m.setTradeDate(it.getTradeDate());
            m.setMarginBalance(it.getMarginBalance());
            rows.add(m);
        }
        stockMarginDailyRepository.saveAll(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", rows.size());
        return ApiResponse.success(out);
    }
}
