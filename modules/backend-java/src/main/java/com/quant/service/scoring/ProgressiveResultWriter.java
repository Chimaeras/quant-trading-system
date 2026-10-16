package com.quant.service.scoring;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 增量落库写入器：使用独立事务提交进度与明细，保证前端可实时查询。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProgressiveResultWriter {

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertRunningResult(
            String resultId,
            SelectorResult seed,
            boolean clearItems
    ) {
        SelectorResult result = selectorResultRepository.findByResultId(resultId).orElse(new SelectorResult());
        result.setResultId(resultId);
        result.setTradeDate(seed.getTradeDate());
        result.setLookbackDays(seed.getLookbackDays());
        result.setTopN(seed.getTopN());
        result.setMinAvgAmount(seed.getMinAvgAmount());
        result.setMinTrendScore(seed.getMinTrendScore());
        result.setTemplateId(seed.getTemplateId());
        result.setFactorVersion(seed.getFactorVersion());
        result.setStrategyType(seed.getStrategyType());
        result.setRunStatus("RUNNING");
        result.setTotalCandidates(nvl(seed.getTotalCandidates(), 0));
        result.setProcessedCandidates(nvl(seed.getProcessedCandidates(), 0));
        result.setSuccessCandidates(nvl(seed.getSuccessCandidates(), 0));
        result.setFailedCandidates(nvl(seed.getFailedCandidates(), 0));
        result.setLastProcessedKey(nvl(seed.getLastProcessedKey(), ""));
        result.setRequestFingerprint(seed.getRequestFingerprint());
        result.setFactorSnapshot(seed.getFactorSnapshot() != null ? seed.getFactorSnapshot() : Map.of());
        selectorResultRepository.save(result);
        if (clearItems) {
            selectorResultItemRepository.deleteByResultId(resultId);
        }
    }

    /**
     * 写入通过质量闸的全截面候选（按得分排序 rankNo），供打分回测 Rank IC 使用；
     * 前端/API 展示仍按父结果 {@code topN} 截取。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replaceResultItems(String resultId, List<SelectorResultItem> items) {
        selectorResultItemRepository.deleteByResultId(resultId);
        if (items == null || items.isEmpty()) {
            return;
        }
        int rank = 1;
        for (SelectorResultItem item : items) {
            item.setResultId(resultId);
            item.setRankNo(rank++);
            sanitizeNumericFields(item);
        }
        selectorResultItemRepository.saveAll(items);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveItemIfAbsent(SelectorResultItem item) {
        sanitizeNumericFields(item);
        Optional<SelectorResultItem> existing = selectorResultItemRepository.findByResultIdAndStockCodeAndExchangeType(
                item.getResultId(), item.getStockCode(), item.getExchangeType()
        );
        if (existing.isPresent()) {
            return false;
        }
        try {
            selectorResultItemRepository.save(item);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Skip progressive insert resultId={} stock={}|{} cause={}",
                    item.getResultId(), item.getStockCode(), item.getExchangeType(), e.getMessage());
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(
            String resultId,
            int totalCandidates,
            int processedCandidates,
            int successCandidates,
            int failedCandidates,
            String lastProcessedKey
    ) {
        selectorResultRepository.findByResultId(resultId).ifPresent(result -> {
            result.setRunStatus("RUNNING");
            result.setTotalCandidates(Math.max(0, totalCandidates));
            result.setProcessedCandidates(Math.max(0, processedCandidates));
            result.setSuccessCandidates(Math.max(0, successCandidates));
            result.setFailedCandidates(Math.max(0, failedCandidates));
            result.setLastProcessedKey(nvl(lastProcessedKey, ""));
            selectorResultRepository.save(result);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishResult(
            String resultId,
            String runStatus,
            int totalCandidates,
            int processedCandidates,
            int successCandidates,
            int failedCandidates
    ) {
        selectorResultRepository.findByResultId(resultId).ifPresent(result -> {
            result.setRunStatus(nvl(runStatus, "SUCCESS"));
            result.setTotalCandidates(Math.max(0, totalCandidates));
            result.setProcessedCandidates(Math.max(0, processedCandidates));
            result.setSuccessCandidates(Math.max(0, successCandidates));
            result.setFailedCandidates(Math.max(0, failedCandidates));
            selectorResultRepository.save(result);
        });
    }

    private static <T> T nvl(T value, T def) {
        return value != null ? value : def;
    }

    private static void sanitizeNumericFields(SelectorResultItem item) {
        // NOT NULL 列：null/NaN 只能退化为 0（列不允许 NULL，见 V2 DDL）
        item.setFinalScore(safe(item.getFinalScore()));
        item.setTrendScore(safe(item.getTrendScore()));
        item.setMomentumScore(safe(item.getMomentumScore()));
        item.setVolumeScore(safe(item.getVolumeScore()));
        item.setRiskPenalty(safe(item.getRiskPenalty()));
        // 可 NULL 列：null/NaN 必须保留为 NULL，不得写成 0。
        // 这五列在 TREND / MULTI_DIM / COMPREHENSIVE 三套策略上历史上「整列 100% 为 0」，
        // 那是「未计算」的占位 0，会被研究侧当特征读走（见 V25 / V30 迁移的实测证据）。
        item.setRet20(safeNullable(item.getRet20()));
        item.setRet60(safeNullable(item.getRet60()));
        item.setVolatility20(safeNullable(item.getVolatility20()));
        item.setMaxDrawdown60(safeNullable(item.getMaxDrawdown60()));
        item.setAvgAmount20(safeNullable(item.getAvgAmount20()));
    }

    private static double safe(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return 0d;
        }
        return value;
    }

    /** 可 NULL 列的清洗：非有限值与 null 一律保留为 NULL，保证「未计算」可被表达。 */
    private static Double safeNullable(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return value;
    }
}
