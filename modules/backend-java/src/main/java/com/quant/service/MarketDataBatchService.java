package com.quant.service;

import com.quant.exception.BusinessException;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MP-014 务实原子批次：历史更新时生成 batch_id，统计 raw/factor/qfq 覆盖，
 * 并在 raw=0 且 qfq&gt;0 时拒绝 PUBLISHED（仅 STAGED / FAILED）。
 *
 * <p>完整 staging→publish 编排为后续；本服务提供一致性检查清单与可选 raw 回填触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataBatchService {

    public static final String PUBLISH_STAGED = "STAGED";
    public static final String PUBLISH_PUBLISHED = "PUBLISHED";
    public static final String PUBLISH_FAILED = "FAILED";

    private final HistoricalDataService historicalDataService;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final AdjustFactorRepository adjustFactorRepository;

    /**
     * 单标的区间更新 + 一致性检查（可选触发 raw 回填）。
     *
     * @param triggerRawBackfill 为 true 时尝试调用现有 raw/factor 回填
     */
    public Map<String, Object> updateWithBatchChecklist(
            String stockCode,
            String exchangeType,
            LocalDate startDate,
            LocalDate endDate,
            boolean triggerRawBackfill) {
        String batchId = UUID.randomUUID().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch_id", batchId);
        out.put("stock_code", stockCode);
        out.put("exchange_type", exchangeType);
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());

        List<String> checklist = new ArrayList<>();
        try {
            Map<String, Object> qfqRes = historicalDataService.updateHistoryInRange(
                    stockCode, exchangeType, startDate, endDate);
            out.put("qfq_update", qfqRes);
            checklist.add("qfq_update:ok");

            Map<String, Object> rawRes = Map.of();
            if (triggerRawBackfill) {
                try {
                    rawRes = historicalDataService.backfillRawAndDeriveAdjustFactors(
                            stockCode, exchangeType, startDate, endDate);
                    checklist.add("raw_backfill:ok");
                } catch (Exception e) {
                    checklist.add("raw_backfill:failed:" + e.getMessage());
                    log.warn("batch {} raw backfill failed: {}", batchId, e.getMessage());
                }
            } else {
                checklist.add("raw_backfill:skipped");
            }
            out.put("raw_update", rawRes);

            long qfqCount = marketDataRepository.countByStockCodeAndExchangeTypeAndTradeDateBetween(
                    stockCode, exchangeType, startDate, endDate);
            long rawCount = marketPriceRawRepository.countByStockCodeAndExchangeTypeAndTradeDateBetween(
                    stockCode, exchangeType, startDate, endDate);
            long factorCount = adjustFactorRepository.countByStockCodeAndExchangeTypeAndTradeDateBetween(
                    stockCode, exchangeType, startDate, endDate);

            out.put("qfq_count", qfqCount);
            out.put("raw_count", rawCount);
            out.put("factor_count", factorCount);

            String publishStatus;
            String reason;
            if (qfqCount > 0 && rawCount == 0) {
                publishStatus = PUBLISH_STAGED;
                reason = "refuse PUBLISHED: raw_count==0 while qfq_count>0 for range (MP-014 consistency)";
                checklist.add("consistency:FAIL_RAW_MISSING");
            } else if (qfqCount == 0 && rawCount == 0) {
                publishStatus = PUBLISH_FAILED;
                reason = "no qfq/raw rows in range";
                checklist.add("consistency:FAIL_EMPTY");
            } else if (rawCount > 0 && factorCount == 0) {
                publishStatus = PUBLISH_STAGED;
                reason = "raw present but factor_count==0 — staged until factors derived";
                checklist.add("consistency:STAGED_FACTOR_GAP");
            } else {
                publishStatus = PUBLISH_PUBLISHED;
                reason = "qfq/raw/factor present for range (pragmatic publish)";
                checklist.add("consistency:OK");
            }
            out.put("publish_status", publishStatus);
            out.put("publish_reason", reason);
            out.put("consistency_checklist", checklist);
            out.put("note", "MP-014 pragmatic batch: full atomic staging tables are future work");
            return out;
        } catch (Exception e) {
            // 更新过程异常（数据拉取/DB 写入失败等）不应伪装为成功，
            // 否则调用方仅凭 HTTP 200 / ApiResponse.code=success 无法发现数据未入库。
            // 重新抛出为 BusinessException → 全局异常处理器返回 HTTP 400。
            log.error("batch {} update failed: {}", out.get("batch_id"), e.getMessage(), e);
            throw new BusinessException("数据批量更新失败: " + e.getMessage());
        }
    }
}
