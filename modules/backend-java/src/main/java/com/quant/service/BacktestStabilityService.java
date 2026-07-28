package com.quant.service;

import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.dto.ScoringBacktestResponse.PeriodStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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

    private final ScoringBacktestService scoringBacktestService;

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

        out.put("verdict", buildVerdict(first, second, h));
        out.put("note", "按日历中点切分前后半段；次日开盘入场+持有期末收盘出场（T+1），无未来函数；"
                + "仅作分段稳定性研究，存在幸存者偏差");
        return out;
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
