package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.service.TradingCalendarService;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 涨停基因 / 短线情绪生态（全部从现有 K 线推导，无外部数据依赖）。
 *
 * <p>提供：
 * <ul>
 *   <li>单票涨停/跌停标记（复用 {@link StrategyBacktestEngine#isLimitUpAtClose} + {@link TradingLimitRules}）；</li>
 *   <li>连板天数（连续涨停）；</li>
 *   <li>市场情绪快照：涨停家数、跌停家数、最高连板、晋级率（前日涨停股次日续涨停比例）、炸板率；</li>
 *   <li>涨停次日溢价统计（高开率/次日涨幅/收涨概率）。</li>
 * </ul>
 *
 * <p>用途：作为 T+1 打分的新因子与候选解释（涨停基因）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LimitUpEcologyService {

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final TradingCalendarService tradingCalendarService;

    /** 市场情绪快照（指定交易日）。lookbackDays 用于连板/晋级率回溯。 */
    public Map<String, Object> ecology(LocalDate tradeDate, int lookbackDays) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);

        // 全市场区间行情（单次批量查询），按 stock key 分组
        List<MarketData> all = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
        Map<String, List<MarketData>> byStock = all.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getStockCode() + "|" + m.getExchangeType(), LinkedHashMap::new, Collectors.toList()));

        List<MarketData> todayBars = marketDataRepository.findByTradeDate(tradeDate);
        Map<String, MarketData> todayByKey = todayBars.stream()
                .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));
        Map<String, String> nameByKey = nameMap();

        int limitUpCount = 0;
        int limitDownCount = 0;
        int maxLianban = 0;
        int brokenBoard = 0; // 盘中触及涨停但收盘未封
        int touchedLimitUp = 0; // 盘中触及涨停（含封住）
        List<String> limitUpKeys = new ArrayList<>();
        Map<String, Integer> lianbanByKey = new HashMap<>();

        for (Map.Entry<String, MarketData> e : todayByKey.entrySet()) {
            String key = e.getKey();
            MarketData bar = e.getValue();
            List<MarketData> stockBars = byStock.getOrDefault(key, List.of());
            double preClose = resolvePreClose(bar, stockBars);
            String name = nameByKey.get(key);
            double limitPct = TradingLimitRules.resolveLimitPct(bar.getStockCode(), bar.getExchangeType(), name);

            boolean touched = touchedLimitUp(bar, preClose, limitPct);
            boolean closedUp = StrategyBacktestEngine.isLimitUpAtClose(bar, preClose, limitPct);
            if (touched) {
                touchedLimitUp++;
                if (closedUp) {
                    limitUpCount++;
                    limitUpKeys.add(key);
                    int lianban = consecutiveLimitUpDays(stockBars, bar, name);
                    lianbanByKey.put(key, lianban);
                    maxLianban = Math.max(maxLianban, lianban);
                } else {
                    brokenBoard++; // 炸板
                }
            }
            if (StrategyBacktestEngine.isLimitDownAtClose(bar, preClose, limitPct)) {
                limitDownCount++;
            }
        }

        // 晋级率 = 前日涨停且今日续涨停 / 前日涨停数
        double promotionRate = 0;
        LocalDate prevDay = tradingCalendarService.findTradingDays(
                tradeDate.minusDays(7), tradeDate.minusDays(1)).stream().reduce((a, b) -> b).orElse(null);
        if (prevDay != null) {
            Map<String, MarketData> prevByKey = marketDataRepository.findByTradeDate(prevDay).stream()
                    .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));
            List<MarketData> prevRangeBars = byStock.isEmpty() ? List.of()
                    : marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                            prevDay.minusDays(7), prevDay);
            Map<String, List<MarketData>> prevByStock = prevRangeBars.stream()
                    .collect(Collectors.groupingBy(
                            m -> m.getStockCode() + "|" + m.getExchangeType(), LinkedHashMap::new, Collectors.toList()));
            List<String> prevLimitUpKeys = new ArrayList<>();
            for (Map.Entry<String, MarketData> e : prevByKey.entrySet()) {
                String key = e.getKey();
                MarketData bar = e.getValue();
                double preClose = resolvePreClose(bar, prevByStock.getOrDefault(key, List.of()));
                double limitPct = TradingLimitRules.resolveLimitPct(
                        bar.getStockCode(), bar.getExchangeType(), nameByKey.get(key));
                if (StrategyBacktestEngine.isLimitUpAtClose(bar, preClose, limitPct)) {
                    prevLimitUpKeys.add(key);
                }
            }
            if (!prevLimitUpKeys.isEmpty()) {
                long promoted = prevLimitUpKeys.stream().filter(limitUpKeys::contains).count();
                promotionRate = (double) promoted / prevLimitUpKeys.size();
            }
        }

        out.put("trade_date", tradeDate.toString());
        out.put("limit_up_count", limitUpCount);
        out.put("limit_down_count", limitDownCount);
        out.put("max_lianban", maxLianban);
        out.put("promotion_rate", Math.round(promotionRate * 10000.0) / 10000.0);
        out.put("broken_board_rate", touchedLimitUp > 0
                ? Math.round((double) brokenBoard / touchedLimitUp * 10000.0) / 10000.0 : 0);
        out.put("touched_limit_up_count", touchedLimitUp);
        out.put("note", "涨停/跌停按收盘是否落在涨跌停价容差带判定（TradingLimitRules 板块/ST 规则）；"
                + "晋级率=前日涨停股次日续涨停占比；炸板率=盘中触及涨停但收盘未封占比；全部由本地 K 线推导");
        return out;
    }

    /**
     * 单只股票的涨停基因特征（供 T+1 打分）：当日是否涨停、连板天数、量比、换手率。
     *
     * @param bars      该股区间行情（升序，含 tradeDate 当日）
     * @param todayBar  当日 bar
     * @param stockName 名称（用于 ST 涨跌停幅度）
     */
    public Map<String, Object> stockLimitUpFeatures(List<MarketData> bars, MarketData todayBar, String stockName) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (todayBar == null) {
            f.put("available", false);
            return f;
        }
        double preClose = resolvePreClose(todayBar, bars);
        double limitPct = TradingLimitRules.resolveLimitPct(todayBar.getStockCode(), todayBar.getExchangeType(), stockName);
        boolean closedUp = StrategyBacktestEngine.isLimitUpAtClose(todayBar, preClose, limitPct);
        boolean closedDown = StrategyBacktestEngine.isLimitDownAtClose(todayBar, preClose, limitPct);
        boolean touched = touchedLimitUp(todayBar, preClose, limitPct);

        int lianban = closedUp ? consecutiveLimitUpDays(bars, todayBar, stockName) : 0;
        double volumeRatio = volumeRatio(bars);
        double turnover = todayBar.getTurnoverRate() != null ? todayBar.getTurnoverRate().doubleValue() : 0;

        f.put("available", true);
        f.put("is_limit_up", closedUp);
        f.put("is_limit_down", closedDown);
        f.put("touched_limit_up", touched);
        f.put("lianban_days", lianban);
        f.put("volume_ratio", Math.round(volumeRatio * 100.0) / 100.0);
        f.put("turnover_rate", Math.round(turnover * 100.0) / 100.0);
        f.put("change_pct", todayBar.getChangePct() != null ? todayBar.getChangePct().doubleValue() : 0);
        return f;
    }

    /**
     * <b>历史口径</b>涨停次日溢价统计：汇总 tradeDate 之前所有已完成的"涨停→次日"事件。
     *
     * <p>与 {@link #nextDayPremium(LocalDate, int)} 的关键区别：
     * <ul>
     *   <li>{@code nextDayPremium(tradeDate, ...)} 统计的是 <b>tradeDate 当天涨停的股票在 T+1 的表现</b>，
     *       必须读取 T+1 数据——在次日核销（settle）阶段合法，但在 T 日候选<b>生成</b>阶段属未来函数。</li>
     *   <li>本方法只统计 <b>信号日 ≤ tradeDate-2</b> 的涨停股（其次日 ≤ tradeDate-1），
     *       所用数据截止 T-1，可在 T 日收盘后安全调用，不泄漏未来信息。</li>
     * </ul>
     *
     * <p>用于 {@link com.quant.service.T1CandidateService#generateCandidates} 生成阶段的历史高开经验值。
     *
     * @param tradeDate    信号日（T），本方法只读 ≤ T-1 的数据
     * @param lookbackDays 向前回溯的日历日数（与 ecology/nextDayPremium 保持一致口径）
     */
    public Map<String, Object> historicalNextDayPremium(LocalDate tradeDate, int lookbackDays) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 候选"涨停信号日"区间：最早 lookbackDays 前，最晚 tradeDate-2（确保其次日 ≤ tradeDate-1 已已知）
        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        LocalDate lastSignalDay = tradingCalendarService.findTradingDays(tradeDate.minusDays(7), tradeDate.minusDays(1))
                .stream().reduce((a, b) -> b).orElse(null);
        if (lastSignalDay == null) {
            out.put("available", false);
            out.put("sample_count", 0);
            out.put("note", "无 tradeDate 前的交易日，无法计算历史涨停次日溢价");
            return out;
        }
        // 批量取区间行情
        List<MarketData> all = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, lastSignalDay);
        Map<String, List<MarketData>> byStock = all.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getStockCode() + "|" + m.getExchangeType(), LinkedHashMap::new, Collectors.toList()));
        // 次日行情：需覆盖到 tradeDate-1（最后一个信号日 lastSignalDay 的次日可能 = tradeDate-1）
        LocalDate dataEnd = tradingCalendarService.findTradingDays(lastSignalDay, lastSignalDay.plusDays(7))
                .stream().filter(d -> d.isAfter(lastSignalDay) && !d.isAfter(tradeDate.minusDays(1)))
                .findFirst().orElse(null);
        Map<String, String> nameByKey = nameMap();

        List<double[]> premiums = new ArrayList<>(); // [高开, 次日收盘涨幅]
        int highOpenCount = 0;
        int upCount = 0;
        for (Map.Entry<String, List<MarketData>> e : byStock.entrySet()) {
            String key = e.getKey();
            List<MarketData> bars = e.getValue();
            String name = nameByKey.get(key);
            for (int i = 0; i < bars.size(); i++) {
                MarketData bar = bars.get(i);
                double preClose = resolvePreClose(bar, bars.subList(0, i + 1));
                double limitPct = TradingLimitRules.resolveLimitPct(bar.getStockCode(), bar.getExchangeType(), name);
                if (!StrategyBacktestEngine.isLimitUpAtClose(bar, preClose, limitPct)) {
                    continue;
                }
                // 找该股在涨停信号日之后的第一个交易日（≤ tradeDate-1）
                MarketData next = null;
                for (int j = i + 1; j < bars.size(); j++) {
                    if (bars.get(j).getTradeDate().isAfter(bar.getTradeDate())) {
                        next = bars.get(j);
                        break;
                    }
                }
                if (next == null || next.getOpenPrice() == null || next.getClosePrice() == null
                        || bar.getClosePrice() == null || bar.getClosePrice().doubleValue() <= 0) {
                    continue;
                }
                double gap = next.getOpenPrice().doubleValue() / bar.getClosePrice().doubleValue() - 1;
                double ret = next.getClosePrice().doubleValue() / bar.getClosePrice().doubleValue() - 1;
                premiums.add(new double[]{gap, ret});
                if (gap > 0) highOpenCount++;
                if (ret > 0) upCount++;
            }
        }
        int n = premiums.size();
        out.put("available", n > 0);
        out.put("sample_count", n);
        if (n > 0) {
            double avgGap = premiums.stream().mapToDouble(p -> p[0]).average().orElse(0);
            double avgRet = premiums.stream().mapToDouble(p -> p[1]).average().orElse(0);
            out.put("high_open_rate", Math.round((double) highOpenCount / n * 10000.0) / 10000.0);
            out.put("avg_gap", Math.round(avgGap * 10000.0) / 10000.0);
            out.put("avg_next_ret", Math.round(avgRet * 10000.0) / 10000.0);
            out.put("up_rate", Math.round((double) upCount / n * 10000.0) / 10000.0);
        }
        out.put("range_start", rangeStart.toString());
        out.put("last_signal_day", lastSignalDay.toString());
        out.put("data_end", dataEnd != null ? dataEnd.toString() : null);
        out.put("note", "历史涨停次日溢价（信号日 ≤ " + lastSignalDay + "，数据截止 ≤ T-1），生成阶段无未来信息");
        return out;
    }

    /** 涨停次日溢价统计（tradeDate 涨停股在 nextTradeDay 的表现），用于候选解释与验证。 */
    public Map<String, Object> nextDayPremium(LocalDate tradeDate, int topLimitUpCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate nextDay = tradingCalendarService.findTradingDays(tradeDate, tradeDate.plusDays(7))
                .stream().filter(d -> !d.isEqual(tradeDate)).findFirst().orElse(null);
        if (nextDay == null) {
            out.put("available", false);
            out.put("note", "无次日交易日");
            return out;
        }
        List<MarketData> todayBars = marketDataRepository.findByTradeDate(tradeDate);
        Map<String, MarketData> nextByKey = marketDataRepository.findByTradeDate(nextDay).stream()
                .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a));
        // 前一交易日行情，用于 preClose 缺失时回退（与 ecology() 口径一致）
        LocalDate prevDay = tradingCalendarService.findTradingDays(tradeDate.minusDays(7), tradeDate.minusDays(1))
                .stream().reduce((a, b) -> b).orElse(null);
        Map<String, MarketData> prevByKey = prevDay != null
                ? marketDataRepository.findByTradeDate(prevDay).stream()
                .collect(Collectors.toMap(m -> m.getStockCode() + "|" + m.getExchangeType(), Function.identity(), (a, b) -> a))
                : Map.of();
        Map<String, String> nameByKey = nameMap();

        List<double[]> premiums = new ArrayList<>(); // [高开, 次日收盘涨幅, 次日最高涨幅]
        int highOpenCount = 0;
        int upCount = 0;
        for (MarketData bar : todayBars) {
            String key = bar.getStockCode() + "|" + bar.getExchangeType();
            MarketData prevBar = prevByKey.get(key);
            // preClose 优先取 bar.preClose，缺失时回退到前一交易日收盘（避免 preClose=null 时漏判涨停）
            double preClose = resolvePreClose(bar, prevBar != null ? List.of(prevBar, bar) : List.of(bar));
            double limitPct = TradingLimitRules.resolveLimitPct(bar.getStockCode(), bar.getExchangeType(), nameByKey.get(key));
            if (!StrategyBacktestEngine.isLimitUpAtClose(bar, preClose, limitPct)) {
                continue;
            }
            MarketData next = nextByKey.get(key);
            if (next == null || next.getOpenPrice() == null || next.getClosePrice() == null
                    || bar.getClosePrice() == null || bar.getClosePrice().doubleValue() <= 0) {
                continue;
            }
            double gap = next.getOpenPrice().doubleValue() / bar.getClosePrice().doubleValue() - 1;
            double ret = next.getClosePrice().doubleValue() / bar.getClosePrice().doubleValue() - 1;
            double highRet = (next.getHighPrice() != null && next.getHighPrice().doubleValue() > 0)
                    ? next.getHighPrice().doubleValue() / bar.getClosePrice().doubleValue() - 1 : ret;
            premiums.add(new double[]{gap, ret, highRet});
            if (gap > 0) highOpenCount++;
            if (ret > 0) upCount++;
        }
        int n = premiums.size();
        out.put("available", n > 0);
        out.put("sample_count", n);
        if (n > 0) {
            double avgGap = premiums.stream().mapToDouble(p -> p[0]).average().orElse(0);
            double avgRet = premiums.stream().mapToDouble(p -> p[1]).average().orElse(0);
            double avgHigh = premiums.stream().mapToDouble(p -> p[2]).average().orElse(0);
            out.put("high_open_rate", Math.round((double) highOpenCount / n * 10000.0) / 10000.0);
            out.put("avg_gap", Math.round(avgGap * 10000.0) / 10000.0);
            out.put("avg_next_ret", Math.round(avgRet * 10000.0) / 10000.0);
            out.put("avg_next_high_ret", Math.round(avgHigh * 10000.0) / 10000.0);
            out.put("up_rate", Math.round((double) upCount / n * 10000.0) / 10000.0);
        }
        out.put("trade_date", tradeDate.toString());
        out.put("next_trade_date", nextDay.toString());
        out.put("note", "涨停股次日溢价：高开率=次日开盘>当日收盘占比；avg_next_ret=次日收盘相对当日收盘平均涨幅");
        return out;
    }

    /** 连续涨停天数（含当日）。 */
    private int consecutiveLimitUpDays(List<MarketData> bars, MarketData todayBar, String stockName) {
        if (bars == null || bars.isEmpty()) {
            return 1;
        }
        int days = 0;
        List<MarketData> seq = new ArrayList<>(bars);
        for (int i = seq.size() - 1; i >= 0; i--) {
            MarketData bar = seq.get(i);
            double preClose = resolvePreClose(bar, i > 0 ? seq.subList(0, i + 1) : List.of());
            double limitPct = TradingLimitRules.resolveLimitPct(bar.getStockCode(), bar.getExchangeType(), stockName);
            if (StrategyBacktestEngine.isLimitUpAtClose(bar, preClose, limitPct)) {
                days++;
            } else {
                break;
            }
        }
        return Math.max(days, 1);
    }

    /** 盘中是否触及涨停价（high 落在涨停价容差带内）。 */
    private boolean touchedLimitUp(MarketData bar, double preClose, double limitPct) {
        if (bar.getHighPrice() == null || bar.getHighPrice().doubleValue() <= 0 || preClose <= 0) {
            return false;
        }
        double limitUpPrice = preClose * (1 + limitPct);
        double tol = 0.001 * limitUpPrice;
        double high = bar.getHighPrice().doubleValue();
        return high >= limitUpPrice - tol && high <= limitUpPrice + tol;
    }

    /** 前收：优先 bar.preClose，否则取该股前一根 K 的收盘。 */
    private static double resolvePreClose(MarketData bar, List<MarketData> bars) {
        if (bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
            return bar.getPreClose().doubleValue();
        }
        if (bars != null) {
            for (int i = bars.size() - 1; i >= 0; i--) {
                MarketData b = bars.get(i);
                if (b.getTradeDate().isBefore(bar.getTradeDate()) && b.getClosePrice() != null
                        && b.getClosePrice().doubleValue() > 0) {
                    return b.getClosePrice().doubleValue();
                }
            }
        }
        return 0;
    }

    /** 量比 = 当日成交量 / 近 5 日（不含当日）日均成交量。 */
    private static double volumeRatio(List<MarketData> bars) {
        if (bars == null || bars.size() < 2) {
            return 0;
        }
        MarketData last = bars.get(bars.size() - 1);
        long lastVol = last.getVolume() != null ? last.getVolume() : 0;
        long sum = 0;
        int count = 0;
        for (int i = bars.size() - 2; i >= 0 && count < 5; i--) {
            MarketData b = bars.get(i);
            sum += b.getVolume() != null ? b.getVolume() : 0;
            count++;
        }
        if (count == 0 || sum <= 0) {
            return 0;
        }
        return (double) lastVol / (sum / (double) count);
    }

    private Map<String, String> nameMap() {
        Map<String, String> map = new HashMap<>();
        for (com.quant.entity.StockInfo s : stockInfoRepository.findAll()) {
            map.put(s.getStockCode() + "|" + s.getExchangeType(), s.getStockName());
        }
        return map;
    }
}
