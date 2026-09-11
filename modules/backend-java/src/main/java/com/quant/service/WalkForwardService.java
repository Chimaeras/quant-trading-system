package com.quant.service;

import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.dto.ScoringBacktestResponse.PeriodStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分段稳定性分析（非 Walk-Forward / 非样本外）。
 *
 * <p>口径：
 * <ul>
 *   <li>将 [startDate, endDate] 按<b>交易日</b>等分为 {@code windows} 个<b>连续非重叠</b>窗口
 *       （用交易日历定位窗口边界，各窗口交易日数≈均分；交易日历未初始化时退回自然日等分）。</li>
 *   <li>每个窗口复用 {@link ScoringBacktestService#run} 计算该窗口内 TopN 在指定持有期的收益。</li>
 *   <li>信号日与可成交时点分离（次日开盘入场、持有期末收盘出场），与打分回测一致；无未来函数。</li>
 *   <li>仅作分段稳定性研究，<b>不是</b>真正的 walk-forward / 样本外验证，不做自动参数搜索。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalkForwardService {

    private static final int MIN_WINDOWS = 2;
    private static final int MAX_WINDOWS = 12;
    private static final int DEFAULT_WINDOWS = 4;
    private static final int DEFAULT_TOP_N = 10;
    private static final int DEFAULT_HOLDING_DAY = 5;

    private final ScoringBacktestService scoringBacktestService;
    private final TradingCalendarService tradingCalendarService;

    /**
     * 滚动窗口稳定性回测。
     *
     * <p>刻意不加 @Transactional：这里要在一次调用内跑 w 个（最多 12）完整回测，
     * Hibernate 6 的连接是"首次取用即绑定、事务结束才归还"，包在一个事务里会让单个
     * 请求在整个多窗口跑批期间独占一个池连接（dev 池仅 10），并发几个请求就把连接池打穿。
     * 每次 run/查询各自的事务已经足够，窗口之间无需原子性。
     */
    public Map<String, Object> run(String strategy, LocalDate startDate, LocalDate endDate,
                                   Integer windows, Integer topN, Integer holdingDay) {
        int w = clamp(windows, MIN_WINDOWS, MAX_WINDOWS, DEFAULT_WINDOWS);
        int n = topN != null && topN > 0 ? topN : DEFAULT_TOP_N;
        int h = holdingDay != null && holdingDay > 0 ? holdingDay : DEFAULT_HOLDING_DAY;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strategy);
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("windows", w);
        out.put("top_n", n);
        out.put("holding_day", h);

        List<LocalDate[]> windowRanges = splitTradingDayWindows(startDate, endDate, w);
        List<Map<String, Object>> windowStats = new ArrayList<>();

        for (int i = 0; i < w; i++) {
            LocalDate ws = windowRanges.get(i)[0];
            LocalDate we = windowRanges.get(i)[1];

            ScoringBacktestRequest req = new ScoringBacktestRequest();
            req.setStrategyType(strategy);
            req.setStartDate(ws);
            req.setEndDate(we);
            req.setTopN(n);
            req.setHoldingDays(List.of(h));

            ScoringBacktestResponse resp = scoringBacktestService.run(req);
            PeriodStat stat = resp.getHoldingPeriodStats() != null
                    ? resp.getHoldingPeriodStats().get(h) : null;

            Map<String, Object> wsMap = new LinkedHashMap<>();
            wsMap.put("window_no", i + 1);
            wsMap.put("start_date", ws.toString());
            wsMap.put("end_date", we.toString());
            wsMap.put("signal_days", resp.getTotalTradingDays());
            wsMap.put("avg_return", stat != null ? round4(stat.getAvgReturn()) : 0.0);
            wsMap.put("win_rate", stat != null ? round4(stat.getWinRate()) : 0.0);
            wsMap.put("observations", stat != null ? stat.getTotalObservations() : 0);
            windowStats.add(wsMap);
        }
        out.put("window_stats", windowStats);
        out.put("analysis_kind", "SEGMENT_STABILITY");
        out.put("is_oos", false);

        out.put("note", "【分段稳定性 · 非 Walk-Forward · 非样本外】连续非重叠窗口（交易日等分，"
                + "交易日历未初始化时退回自然日等分）；次日开盘入场+持有期末收盘出场（T+1）；"
                + "无训练/校准/purge/embargo；存在幸存者偏差。真正滚动实验见后续入口。");
        return out;
    }

    /**
     * 将 [start, end] 按交易日等分为 w 段（各窗口交易日数≈均分），返回每段的 [起, 止]。
     * 交易日历无数据（未初始化）时退回自然日等分。
     */
    private List<LocalDate[]> splitTradingDayWindows(LocalDate start, LocalDate end, int w) {
        List<LocalDate> days = tradingCalendarService.findTradingDays(start, end);
        List<LocalDate[]> out = new ArrayList<>();
        if (days.size() < w) {
            List<LocalDate> bounds = splitRange(start, end, w);
            for (int i = 0; i < w; i++) {
                LocalDate ws = bounds.get(i);
                LocalDate we = i == w - 1 ? end : bounds.get(i + 1).minusDays(1);
                out.add(new LocalDate[]{ws, we});
            }
            return out;
        }
        int base = days.size() / w;
        int rem = days.size() % w;
        int idx = 0;
        for (int i = 0; i < w; i++) {
            int size = base + (i < rem ? 1 : 0);
            LocalDate ws = days.get(idx);
            LocalDate we = days.get(Math.min(days.size() - 1, idx + size - 1));
            out.add(new LocalDate[]{ws, we});
            idx += size;
        }
        return out;
    }

    /** 将 [start, end] 按自然日等分为 w 段，返回 w+1 个边界点（最后边界为 end+1，由调用方处理）。 */
    private static List<LocalDate> splitRange(LocalDate start, LocalDate end, int w) {
        long total = ChronoUnit.DAYS.between(start, end) + 1;
        long step = Math.max(1, total / w);
        List<LocalDate> bounds = new ArrayList<>();
        for (int i = 0; i < w; i++) {
            bounds.add(start.plusDays(i * step));
        }
        bounds.add(end.plusDays(1));
        return bounds;
    }

    private static int clamp(Integer v, int min, int max, int def) {
        if (v == null || v < min) return def;
        return Math.min(v, max);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
