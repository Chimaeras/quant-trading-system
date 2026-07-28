package com.quant.service;

import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量驾驶舱：缺失交易日、list_date 缺失、更新滞后告警、复权口径披露。
 *
 * <p>口径：
 * <ul>
 *   <li>缺失交易日：用 market_data 的 distinct trade_date 与「自然日排除周末」的预期交易日比对；
 *       该预期未剔除法定节假日，长假前后略有偏差（已在前端提示）。</li>
 *   <li>更新滞后：最新交易日距今天数 > 阈值时告警（阈值默认 3 天，覆盖周末）。</li>
 *   <li>复权口径：当前系统以前复权为准（与 HistoricalDataService 口径一致），此处仅披露。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQualityService {

    private static final long STALE_DAYS_THRESHOLD = 3L;

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> buildQualityReport() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 1. 交易日范围与缺失
        LocalDate minDate = marketDataRepository.findMinTradeDate().orElse(null);
        LocalDate maxDate = marketDataRepository.findMaxTradeDate().orElse(null);
        out.put("earliest_date", minDate != null ? minDate.toString() : "");
        out.put("latest_date", maxDate != null ? maxDate.toString() : "");

        Map<String, Object> missingDays = buildMissingTradingDays(minDate, maxDate);
        out.put("trading_days", missingDays);

        // 2. 更新滞后告警
        Map<String, Object> staleness = buildStaleness(maxDate);
        out.put("update_staleness", staleness);

        // 3. list_date 缺失
        long listDateMissing = stockInfoRepository.countByListDateIsNull();
        Map<String, Object> listDate = new LinkedHashMap<>();
        listDate.put("missing_count", listDateMissing);
        listDate.put("backfillable_count", listDateMissing > 0
                ? marketDataRepository.countBackfillableListDateCandidates() : 0L);
        listDate.put("note", "list_date 缺失时可用本地 K 线最早交易日回填，非交易所官方上市日");
        out.put("list_date", listDate);

        // 4. 复权口径披露
        Map<String, Object> adjust = new LinkedHashMap<>();
        adjust.put("current_basis", "前复权（qfq）");
        adjust.put("scope", "K 线与技术指标计算均使用前复权；模拟成交价以前复权收盘/开盘为准");
        adjust.put("warning", "前复权会因新增除权事件向后修正历史价格，回测结果可能随数据更新而变化");
        out.put("adjust_factor", adjust);

        // 5. 整体健康度
        out.put("health", buildHealth(missingDays, staleness, listDateMissing));
        return out;
    }

    private Map<String, Object> buildMissingTradingDays(LocalDate minDate, LocalDate maxDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (minDate == null || maxDate == null || minDate.isAfter(maxDate)) {
            m.put("expected", 0);
            m.put("actual", 0);
            m.put("missing_count", 0);
            m.put("missing_dates", List.of());
            m.put("note", "无行情数据");
            return m;
        }
        List<LocalDate> actual = marketDataRepository.findDistinctTradeDatesBetween(minDate, maxDate);
        // 预期交易日：自然日排除周末（未剔除法定节假日，会高估缺失）
        List<LocalDate> expected = new java.util.ArrayList<>();
        for (LocalDate d = minDate; !d.isAfter(maxDate); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                expected.add(d);
            }
        }
        java.util.Set<LocalDate> actualSet = new java.util.HashSet<>(actual);
        List<String> missing = expected.stream()
                .filter(d -> !actualSet.contains(d))
                .map(LocalDate::toString)
                .toList();
        m.put("expected", expected.size());
        m.put("actual", actual.size());
        m.put("missing_count", missing.size());
        m.put("missing_dates", missing.size() > 50 ? missing.subList(0, 50) : missing);
        m.put("note", "预期交易日按自然日排除周末估算，未剔除法定节假日；missing_count 偏高属正常");
        return m;
    }

    private Map<String, Object> buildStaleness(LocalDate maxDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        m.put("today", today.toString());
        if (maxDate == null) {
            m.put("days_behind", null);
            m.put("stale", true);
            m.put("warning", "无行情数据");
            return m;
        }
        long daysBehind = ChronoUnit.DAYS.between(maxDate, today);
        m.put("latest_date", maxDate.toString());
        m.put("days_behind", daysBehind);
        boolean stale = daysBehind > STALE_DAYS_THRESHOLD;
        m.put("stale", stale);
        m.put("threshold_days", STALE_DAYS_THRESHOLD);
        if (stale) {
            m.put("warning", "最新行情距今 " + daysBehind + " 天，数据可能滞后（阈值 " + STALE_DAYS_THRESHOLD + " 天，覆盖周末）");
        } else {
            m.put("warning", null);
        }
        return m;
    }

    private Map<String, Object> buildHealth(Map<String, Object> missingDays, Map<String, Object> staleness, long listDateMissing) {
        boolean stale = Boolean.TRUE.equals(staleness.get("stale"));
        int missingCount = ((Number) missingDays.getOrDefault("missing_count", 0)).intValue();
        String status;
        if (stale || listDateMissing > 0) {
            status = "warn";
        } else if (missingCount > 0) {
            status = "warn";
        } else {
            status = "ok";
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", status);
        h.put("verdict", "ok".equals(status) ? "数据质量良好"
                : "warn".equals(status) ? "存在数据质量提示，请关注告警项" : "数据质量异常");
        return h;
    }
}
