package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.WatchlistItem;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.WatchlistItemRepository;
import com.quant.service.backtest.TradingLimitRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自选股异动扫描（轮询先行，不依赖 WebSocket）。
 *
 * <p>口径：
 * <ul>
 *   <li>数据来源：已入库的 market_data（最新行情），<b>非实时</b>。</li>
 *   <li>信号日 = 最新行情的 trade_date；无未来函数。</li>
 *   <li>异动类型：涨跌幅突破阈值、成交量放大（vs 近5日均量）、逼近涨跌停、创 N 日新高/新低。</li>
 *   <li>仅返回有至少一个异动信号的标的；允许「今日无异动」。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistAlertService {

    private static final int VOLUME_MA_WINDOW = 5;
    private static final int DEFAULT_LOOKBACK = 20;
    private static final double DEFAULT_PCT_THRESHOLD = 0.05;
    private static final double DEFAULT_VOLUME_RATIO_THRESHOLD = 2.0;
    private static final double NEAR_LIMIT_MARGIN = 0.01;
    /** 日历日余量，确保覆盖 lookback 个交易日。 */
    private static final int CALENDAR_PADDING = 25;

    private final WatchlistItemRepository watchlistItemRepository;
    private final MarketDataRepository marketDataRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> scan(Long userId, Double pctThreshold, Double volumeRatioThreshold,
                                    Integer lookbackDays) {
        double pctThr = pctThreshold != null ? Math.abs(pctThreshold) : DEFAULT_PCT_THRESHOLD;
        double volThr = volumeRatioThreshold != null && volumeRatioThreshold > 0
                ? volumeRatioThreshold : DEFAULT_VOLUME_RATIO_THRESHOLD;
        int lookback = lookbackDays != null && lookbackDays > 0 ? lookbackDays : DEFAULT_LOOKBACK;

        List<WatchlistItem> items = watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);
        out.put("basis", "最新入库行情（非实时）");

        if (items.isEmpty()) {
            out.put("signal_date", "");
            out.put("total_scanned", 0);
            out.put("total_alerted", 0);
            out.put("alerts", List.of());
            out.put("note", "自选股为空，请先添加自选股");
            return out;
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(lookback + CALENDAR_PADDING);
        List<Map<String, Object>> alerts = new ArrayList<>();
        LocalDate latestSignalDate = null;

        for (WatchlistItem item : items) {
            List<MarketData> bars = marketDataRepository
                    .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                            item.getStockCode(), item.getExchangeType(), start, end);
            if (bars.isEmpty()) {
                continue;
            }
            MarketData latest = bars.get(bars.size() - 1);
            if (latestSignalDate == null || latest.getTradeDate().isAfter(latestSignalDate)) {
                latestSignalDate = latest.getTradeDate();
            }

            List<String> signals = buildSignals(bars, latest, item, pctThr, volThr, lookback);
            if (signals.isEmpty()) {
                continue;
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("stock_code", item.getStockCode());
            a.put("exchange_type", item.getExchangeType());
            a.put("stock_name", item.getStockName());
            a.put("signal_date", latest.getTradeDate().toString());
            a.put("latest_close", latest.getClosePrice() != null ? latest.getClosePrice().doubleValue() : null);
            a.put("pct_change", pctChange(latest));
            a.put("volume_ratio", volumeRatio(bars));
            a.put("signals", signals);
            alerts.add(a);
        }

        out.put("signal_date", latestSignalDate != null ? latestSignalDate.toString() : "");
        out.put("total_scanned", items.size());
        out.put("total_alerted", alerts.size());
        out.put("alerts", alerts);
        out.put("note", alerts.isEmpty() ? "基于最新入库行情，无标的触发异动阈值" : "仅列出触发至少一个异动信号的标的");
        return out;
    }

    private List<String> buildSignals(List<MarketData> bars, MarketData latest, WatchlistItem item,
                                      double pctThr, double volThr, int lookback) {
        List<String> signals = new ArrayList<>();
        double pct = pctChange(latest);
        if (pct >= pctThr) {
            signals.add("涨幅突破" + round2(pct * 100) + "%");
        } else if (pct <= -pctThr) {
            signals.add("跌幅突破" + round2(pct * 100) + "%");
        }

        double volRatio = volumeRatio(bars);
        if (volRatio >= volThr) {
            signals.add("成交量放大" + round2(volRatio) + "倍");
        }

        double limitPct = TradingLimitRules.resolveLimitPct(
                item.getStockCode(), item.getExchangeType(), item.getStockName());
        if (pct >= limitPct - NEAR_LIMIT_MARGIN) {
            signals.add("逼近涨停");
        } else if (pct <= -(limitPct - NEAR_LIMIT_MARGIN)) {
            signals.add("逼近跌停");
        }

        int n = Math.min(lookback, bars.size());
        if (n >= 2) {
            double latestClose = latest.getClosePrice() != null ? latest.getClosePrice().doubleValue() : Double.NaN;
            boolean isHigh = true;
            boolean isLow = true;
            for (int i = bars.size() - n; i < bars.size() - 1; i++) {
                double c = bars.get(i).getClosePrice() != null ? bars.get(i).getClosePrice().doubleValue() : Double.NaN;
                if (Double.isNaN(c) || Double.isNaN(latestClose)) {
                    isHigh = false;
                    isLow = false;
                    break;
                }
                if (latestClose <= c) isHigh = false;
                if (latestClose >= c) isLow = false;
            }
            if (isHigh) signals.add("创" + n + "日新高");
            if (isLow) signals.add("创" + n + "日新低");
        }
        return signals;
    }

    private static double pctChange(MarketData bar) {
        if (bar.getChangePct() != null) {
            return bar.getChangePct().doubleValue();
        }
        if (bar.getClosePrice() == null || bar.getPreClose() == null
                || bar.getPreClose().doubleValue() <= 0) {
            return 0.0;
        }
        return bar.getClosePrice().doubleValue() / bar.getPreClose().doubleValue() - 1;
    }

    private static double volumeRatio(List<MarketData> bars) {
        if (bars.size() < 2) {
            return 0.0;
        }
        long latestVol = bars.get(bars.size() - 1).getVolume() != null
                ? bars.get(bars.size() - 1).getVolume() : 0L;
        int maStart = Math.max(0, bars.size() - 1 - VOLUME_MA_WINDOW);
        double sum = 0;
        int count = 0;
        for (int i = maStart; i < bars.size() - 1; i++) {
            long v = bars.get(i).getVolume() != null ? bars.get(i).getVolume() : 0L;
            sum += v;
            count++;
        }
        if (count == 0 || sum <= 0) {
            return 0.0;
        }
        return latestVol / (sum / count);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
