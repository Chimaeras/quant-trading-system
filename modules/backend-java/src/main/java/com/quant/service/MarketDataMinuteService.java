package com.quant.service;

import com.quant.dto.MinuteBarItem;
import com.quant.entity.MarketDataMinute;
import com.quant.exception.BusinessException;
import com.quant.repository.MarketDataMinuteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分钟级行情服务：盯盘 5 分钟 bar 的本地沉淀与读取（D-STALE-BAR-01 修复 4）。
 *
 * <p>为什么需要它：日线 {@code market_data} 撑不起盘中策略，而 5 分钟粒度此前只活在
 * Python 侧的 sqlite「同一交易日重启恢复」缓存里。那张表既不清理也无日期边界，
 * 于是「最近 80 根」会跨月取证，陈旧旧价与当日实时价被拼成假连续序列，均线在断层处
 * 跳变、伪造出金叉买入（实测 601579 的 ma5 算成 22.866，真实应为 34.32）。
 *
 * <p>把分钟线落到主库后：预热优先读**本地连续**分钟线，东方财富退化为兜底；同时
 * 分钟级历史成为可回测、可复现的本地资产，而不是一份随时会被清理的缓存。
 */
@Service
@RequiredArgsConstructor
public class MarketDataMinuteService {

    private static final int MAX_BULK_IMPORT_SIZE = 50_000;
    private static final int DEFAULT_PERIOD_MIN = 5;
    private static final int DEFAULT_LIMIT_PER_CODE = 80;
    private static final int MAX_LIMIT_PER_CODE = 1_000;
    private static final int DEFAULT_LOOKBACK_DAYS = 5;
    private static final int MAX_LOOKBACK_DAYS = 60;

    private final MarketDataMinuteRepository repository;

    /** 批量幂等写入；坏 bar 计数跳过而不抛错，避免一根脏数据毁掉整批预热沉淀。 */
    @Transactional
    public Map<String, Object> bulkUpsert(List<MinuteBarItem> items) {
        long saved = 0;
        long skipped = 0;
        if (items != null && !items.isEmpty()) {
            if (items.size() > MAX_BULK_IMPORT_SIZE) {
                throw new BusinessException("分钟线批量导入条数超上限: " + MAX_BULK_IMPORT_SIZE);
            }
            for (MinuteBarItem item : items) {
                if (!isComplete(item)) {
                    skipped++;
                    continue;
                }
                repository.upsert(
                        item.getStockCode().trim(),
                        item.getPeriodMin(),
                        item.getStartTs(),
                        item.getEndTs(),
                        item.getTradeDate(),
                        item.getOpen(),
                        item.getHigh(),
                        item.getLow(),
                        item.getClose(),
                        item.getVolume() == null ? 0L : item.getVolume(),
                        normaliseSource(item.getSource()));
                saved++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", saved);
        out.put("skipped", skipped);
        return out;
    }

    /** 单根 bar 的价格与时间必须自洽：非正价或 high&lt;low 一律视为坏数据丢弃。 */
    private static boolean isComplete(MinuteBarItem item) {
        if (item.getStockCode() == null || item.getStockCode().isBlank()
                || item.getPeriodMin() == null || item.getPeriodMin() <= 0
                || item.getStartTs() == null || item.getEndTs() == null
                || item.getTradeDate() == null) {
            return false;
        }
        BigDecimal open = item.getOpen();
        BigDecimal high = item.getHigh();
        BigDecimal low = item.getLow();
        BigDecimal close = item.getClose();
        if (open == null || high == null || low == null || close == null) {
            return false;
        }
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0) {
            return false;
        }
        return high.compareTo(low) >= 0;
    }

    private static String normaliseSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        String trimmed = source.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }

    /**
     * 取一批代码的最近 {@code limitPerCode} 根 bar，供 Python 预热使用。
     *
     * <p>先用 {@code lookbackDays} 界定窗口再逐代码取尾部：窗口给足连续交易日，
     * 尾部才是真正连续的一段；这也是本地路径能替代外部 K 线的前提。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> recentByCode(Collection<String> codes, Integer periodMin,
                                           Integer limitPerCode, Integer lookbackDays) {
        int period = periodMin == null || periodMin <= 0 ? DEFAULT_PERIOD_MIN : periodMin;
        int limit = clamp(limitPerCode, DEFAULT_LIMIT_PER_CODE, 1, MAX_LIMIT_PER_CODE);
        int lookback = clamp(lookbackDays, DEFAULT_LOOKBACK_DAYS, 1, MAX_LOOKBACK_DAYS);

        List<String> wanted = new ArrayList<>();
        if (codes != null) {
            for (String code : codes) {
                if (code != null && !code.isBlank()) {
                    wanted.add(code.trim());
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            out.put("bars_by_code", Map.of());
            out.put("total_codes", 0);
            out.put("total_bars", 0);
            return out;
        }

        LocalDate fromDate = LocalDate.now().minusDays(lookback);
        List<MarketDataMinute> rows =
                repository.findByPeriodMinAndStockCodeInAndTradeDateGreaterThanEqualOrderByStockCodeAscStartTsAsc(
                        period, wanted, fromDate);

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (MarketDataMinute bar : rows) {
            grouped.computeIfAbsent(bar.getStockCode(), key -> new ArrayList<>()).add(toPayload(bar));
        }
        int totalBars = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> bars = entry.getValue();
            if (bars.size() > limit) {
                bars = new ArrayList<>(bars.subList(bars.size() - limit, bars.size()));
                entry.setValue(bars);
            }
            totalBars += bars.size();
        }

        out.put("bars_by_code", grouped);
        out.put("total_codes", grouped.size());
        out.put("total_bars", totalBars);
        out.put("from_date", fromDate.toString());
        out.put("period_min", period);
        return out;
    }

    /** 入库情况监控：分钟线是否在持续沉淀。 */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(Integer periodMin) {
        int period = periodMin == null || periodMin <= 0 ? DEFAULT_PERIOD_MIN : periodMin;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period_min", period);
        out.put("total_bars", repository.countByPeriodMin(period));
        var latest = repository.findLatestStartTs(period);
        out.put("latest_start_ts", latest == null ? null
                : latest.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return out;
    }

    /**
     * 清理窗口外的陈旧分钟线。分钟线是资产而非缓存，故保留期远长于 sqlite 缓存
     * （默认由调用方给出），避免重演「缓存无限增长且被当成历史」的老问题。
     */
    @Transactional
    public Map<String, Object> prune(Integer periodMin, Integer keepDays) {
        int period = periodMin == null || periodMin <= 0 ? DEFAULT_PERIOD_MIN : periodMin;
        int keep = clamp(keepDays, 365, 1, 3_650);
        LocalDate cutoff = LocalDate.now().minusDays(keep);
        int removed = repository.deleteOlderThan(period, cutoff);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period_min", period);
        out.put("cutoff_trade_date", cutoff.toString());
        out.put("removed", removed);
        return out;
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private static Map<String, Object> toPayload(MarketDataMinute bar) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", bar.getStockCode());
        payload.put("period_min", bar.getPeriodMin());
        payload.put("start_ts", bar.getStartTs().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        payload.put("end_ts", bar.getEndTs().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        payload.put("trade_date", bar.getTradeDate().toString());
        payload.put("open", bar.getOpenPrice());
        payload.put("high", bar.getHighPrice());
        payload.put("low", bar.getLowPrice());
        payload.put("close", bar.getClosePrice());
        payload.put("volume", bar.getVolume());
        payload.put("source", bar.getSource());
        return payload;
    }
}
