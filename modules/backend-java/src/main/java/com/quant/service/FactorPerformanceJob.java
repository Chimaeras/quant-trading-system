package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 定期计算选股因子 IC/IR（Spearman 秩相关）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorPerformanceJob {

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final MarketDataRepository marketDataRepository;

    @Scheduled(cron = "0 0 3 * * MON")
    @Transactional
    public void computeWeeklyIc() {
        LocalDate end = marketDataRepository.findMaxTradeDate().orElse(LocalDate.now().minusDays(5));
        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(end.minusDays(30), end);
        for (SelectorResult r : results) {
            if (!"SUCCESS".equalsIgnoreCase(r.getRunStatus())) continue;
            try {
                double ic = computeSpearmanIc(r);
                if (!Double.isNaN(ic)) {
                    r.setIcValue(ic);
                    r.setIrValue(ic);
                    selectorResultRepository.save(r);
                    log.info("[factor-ic] resultId={} factorVersion={} ic={}",
                            r.getResultId(), r.getFactorVersion(), ic);
                }
            } catch (Exception e) {
                log.warn("[factor-ic] failed resultId={}: {}", r.getResultId(), e.getMessage());
            }
        }
    }

    private double computeSpearmanIc(SelectorResult result) {
        List<SelectorResultItem> items = selectorResultItemRepository
                .findByResultIdOrderByRankNoAsc(result.getResultId());
        if (items.size() < 5) return Double.NaN;

        LocalDate t0 = result.getTradeDate();
        LocalDate t5 = t0.plusDays(7);
        List<Double> scores = new ArrayList<>();
        List<Double> fwdReturns = new ArrayList<>();

        for (SelectorResultItem item : items) {
            Optional<MarketData> bar0 = marketDataRepository
                    .findByStockCodeAndExchangeTypeAndTradeDate(
                            item.getStockCode(), item.getExchangeType(), t0);
            Optional<MarketData> bar1 = marketDataRepository
                    .findFirstByStockCodeAndExchangeTypeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
                            item.getStockCode(), item.getExchangeType(), t5);
            if (bar0.isEmpty() || bar1.isEmpty()) continue;
            double p0 = bar0.get().getClosePrice().doubleValue();
            double p1 = bar1.get().getClosePrice().doubleValue();
            if (p0 <= 0) continue;
            scores.add(item.getFinalScore());
            fwdReturns.add((p1 / p0 - 1) * 100);
        }
        if (scores.size() < 5) return Double.NaN;
        return spearman(scores, fwdReturns);
    }

    private static double spearman(List<Double> x, List<Double> y) {
        int n = x.size();
        double[] rx = rank(x);
        double[] ry = rank(y);
        double meanX = Arrays.stream(rx).average().orElse(0);
        double meanY = Arrays.stream(ry).average().orElse(0);
        double num = 0, denX = 0, denY = 0;
        for (int i = 0; i < n; i++) {
            double dx = rx[i] - meanX;
            double dy = ry[i] - meanY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }
        if (denX < 1e-12 || denY < 1e-12) return 0;
        return num / Math.sqrt(denX * denY);
    }

    private static double[] rank(List<Double> values) {
        int n = values.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparing(values::get));
        double[] ranks = new double[n];
        for (int r = 0; r < n; r++) {
            ranks[idx[r]] = r + 1;
        }
        return ranks;
    }
}
