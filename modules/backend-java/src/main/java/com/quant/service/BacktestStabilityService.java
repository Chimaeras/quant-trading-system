package com.quant.service;

import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.dto.ScoringBacktestResponse.PeriodStat;
import com.quant.entity.IndexQuote;
import com.quant.repository.IndexQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测分段稳定性报告：将回测区间按日历中点切分为前半段与后半段，
 * 对比两段的胜率/平均收益/盈亏比，判断策略表现是否衰退。
 *
 * <p>口径：
 * <ul>
 *   <li>复用 {@link ScoringBacktestService#run}，按前半段、后半段、全程分别跑一次。</li>
 *   <li>信号日与可成交时点分离（次日开盘入场、持有期末收盘出场），与打分回测一致；无未来函数。</li>
 *   <li>仅作分段稳定性研究，存在幸存者偏差（沿用打分回测口径）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestStabilityService {

    private static final String BENCHMARK_INDEX_CODE = "000300";
    private static final String BENCHMARK_INDEX_NAME = "沪深300";

    private final ScoringBacktestService scoringBacktestService;
    private final IndexQuoteRepository indexQuoteRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> analyze(String strategy, LocalDate startDate, LocalDate endDate,
                                       Integer topN, Integer holdingDay) {
        int n = topN != null && topN > 0 ? topN : 10;
        int h = holdingDay != null && holdingDay > 0 ? holdingDay : 5;

        LocalDate mid = splitMidDate(startDate, endDate);

        ScoringBacktestResponse full = run(strategy, startDate, endDate, n, h);
        ScoringBacktestResponse first = run(strategy, startDate, mid.minusDays(1), n, h);
        ScoringBacktestResponse second = run(strategy, mid, endDate, n, h);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strategy);
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("split_date", mid.toString());
        out.put("top_n", n);
        out.put("holding_day", h);

        out.put("first_half", segmentSummary(first, h, "前半段"));
        out.put("second_half", segmentSummary(second, h, "后半段"));
        out.put("full_period", segmentSummary(full, h, "全程"));

        addBenchmarkAndSegments(out, full, h, startDate, endDate);

        out.put("verdict", buildVerdict(first, second, h));
        out.put("note", "按日历中点切分前后半段；次日开盘入场+持有期末收盘出场（T+1），无未来函数；"
                + "基准=沪深300(000300)，市场分段按指数当日涨跌划分牛/熊；仅作分段稳定性研究，存在幸存者偏差");
        return out;
    }

    /** 沪深300 基准对照 + 牛/熊分段（按指数当日涨跌划分），复用 full 回测的每日胜率。 */
    private void addBenchmarkAndSegments(Map<String, Object> out, ScoringBacktestResponse full,
                                         int holdingDay, LocalDate startDate, LocalDate endDate) {
        List<IndexQuote> quotes = indexQuoteRepository
                .findByIndexCodeAndTradeDateBetweenOrderByTradeDateAsc(BENCHMARK_INDEX_CODE, startDate, endDate);
        if (quotes == null || quotes.size() < 2) {
            out.put("benchmark", Map.of("available", false, "note", "沪深300(000300) 指数数据不足，未计算基准/市场分段"));
            return;
        }
        double firstClose = q(quotes.get(0));
        double lastClose = q(quotes.get(quotes.size() - 1));
        double indexReturn = firstClose > 0 ? lastClose / firstClose - 1 : 0;

        // 每日方向：close[t] >= close[t-1] → bull
        Map<LocalDate, Boolean> bullByDate = new HashMap<>();
        Map<LocalDate, Double> dayIndexRet = new HashMap<>();
        for (int i = 1; i < quotes.size(); i++) {
            double prev = q(quotes.get(i - 1));
            double cur = q(quotes.get(i));
            if (prev > 0 && cur > 0) {
                bullByDate.put(quotes.get(i).getTradeDate(), cur >= prev);
                dayIndexRet.put(quotes.get(i).getTradeDate(), cur / prev - 1);
            }
        }

        // 用 full 回测每日胜率按牛/熊聚合
        Map<LocalDate, Double> winByDate = new HashMap<>();
        for (com.quant.dto.ScoringBacktestResponse.DailyDetail dd : full.getDailyDetails()) {
            if (dd.getTradeDate() != null && dd.getDailyWinRate() != null) {
                Double wr = dd.getDailyWinRate().get(holdingDay);
                if (wr != null) winByDate.put(dd.getTradeDate(), wr);
            }
        }

        double bullWinSum = 0, bearWinSum = 0, bullIdxSum = 0, bearIdxSum = 0;
        int bullDays = 0, bearDays = 0;
        for (Map.Entry<LocalDate, Boolean> e : bullByDate.entrySet()) {
            Boolean bull = e.getValue();
            Double dayRet = dayIndexRet.get(e.getKey());
            Double wr = winByDate.get(e.getKey());
            if (dayRet == null) continue;
            if (Boolean.TRUE.equals(bull)) {
                bullIdxSum += dayRet;
                if (wr != null) { bullWinSum += wr; bullDays++; }
            } else {
                bearIdxSum += dayRet;
                if (wr != null) { bearWinSum += wr; bearDays++; }
            }
        }

        PeriodStat fullStat = full.getHoldingPeriodStats() != null
                ? full.getHoldingPeriodStats().get(holdingDay) : null;
        double strategyAvgReturn = fullStat != null ? fullStat.getAvgReturn() : 0;

        out.put("benchmark", Map.of(
                "available", true,
                "index_code", BENCHMARK_INDEX_CODE,
                "index_name", BENCHMARK_INDEX_NAME,
                "index_return", round4(indexReturn),
                "strategy_avg_return", round4(strategyAvgReturn),
                "outperform", strategyAvgReturn > indexReturn));
        out.put("market_segments", Map.of(
                "bull", Map.of(
                        "days", bullDays,
                        "avg_win_rate", bullDays > 0 ? round4(bullWinSum / bullDays) : 0,
                        "index_return", round4(bullIdxSum)),
                "bear", Map.of(
                        "days", bearDays,
                        "avg_win_rate", bearDays > 0 ? round4(bearWinSum / bearDays) : 0,
                        "index_return", round4(bearIdxSum))));
    }

    private static double q(IndexQuote iq) {
        return iq.getClosePrice() != null ? iq.getClosePrice().doubleValue() : 0;
    }

    private ScoringBacktestResponse run(String strategy, LocalDate start, LocalDate end, int topN, int holdingDay) {
        ScoringBacktestRequest req = new ScoringBacktestRequest();
        req.setStrategyType(strategy);
        req.setStartDate(start);
        req.setEndDate(end);
        req.setTopN(topN);
        req.setHoldingDays(java.util.List.of(holdingDay));
        return scoringBacktestService.run(req);
    }

    private Map<String, Object> segmentSummary(ScoringBacktestResponse resp, int holdingDay, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("signal_days", resp.getTotalTradingDays());
        PeriodStat stat = resp.getHoldingPeriodStats() != null
                ? resp.getHoldingPeriodStats().get(holdingDay) : null;
        if (stat != null) {
            m.put("avg_return", round4(stat.getAvgReturn()));
            m.put("win_rate", round4(stat.getWinRate()));
            m.put("profit_loss_ratio", round4(stat.getProfitLossRatio()));
            m.put("observations", stat.getTotalObservations());
        } else {
            m.put("avg_return", 0.0);
            m.put("win_rate", 0.0);
            m.put("profit_loss_ratio", 0.0);
            m.put("observations", 0);
        }
        return m;
    }

    private String buildVerdict(ScoringBacktestResponse first, ScoringBacktestResponse second, int holdingDay) {
        PeriodStat f = first.getHoldingPeriodStats() != null ? first.getHoldingPeriodStats().get(holdingDay) : null;
        PeriodStat s = second.getHoldingPeriodStats() != null ? second.getHoldingPeriodStats().get(holdingDay) : null;
        if (f == null || s == null || f.getTotalObservations() == 0 || s.getTotalObservations() == 0) {
            return "有效观测不足，难以判断稳定性";
        }
        double fAvg = f.getAvgReturn();
        double sAvg = s.getAvgReturn();
        if (sAvg > fAvg && fAvg > 0) {
            return "后半段表现优于前半段且均为正，策略可能持续有效";
        }
        if (sAvg < fAvg && sAvg < 0) {
            return "后半段表现弱于前半段且转负，策略可能出现衰退";
        }
        if (fAvg > 0 && sAvg > 0) {
            return "前后半段均为正收益，分段表现稳定";
        }
        if (fAvg < 0 && sAvg < 0) {
            return "前后半段均为负收益，策略在该区间持续偏弱";
        }
        return "前后半段表现不一致，稳定性一般，建议结合更多样本";
    }

    /** 按日历日中点切分。 */
    private static LocalDate splitMidDate(LocalDate start, LocalDate end) {
        long total = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        long half = (total + 1) / 2;
        return start.plusDays(half);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
