package com.quant.service;

import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.dto.ScoringBacktestResponse.PeriodStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalkForwardServiceTest {

    @Mock
    private ScoringBacktestService scoringBacktestService;
    @Mock
    private TradingCalendarService tradingCalendarService;

    private WalkForwardService service() {
        return new WalkForwardService(scoringBacktestService, tradingCalendarService);
    }

    private static ScoringBacktestResponse mockResponse(int signalDays, double avgReturn, double winRate, int obs) {
        PeriodStat stat = PeriodStat.builder()
                .avgReturn(avgReturn).winRate(winRate).totalObservations(obs)
                .medianReturn(0).maxReturn(0).minReturn(0).avgWin(0).avgLoss(0).profitLossRatio(0.0)
                .build();
        return ScoringBacktestResponse.builder()
                .totalTradingDays(signalDays)
                .holdingPeriodStats(Map.of(5, stat))
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void runSplitsIntoConsecutiveWindowsAndAggregates() {
        when(scoringBacktestService.run(any(ScoringBacktestRequest.class)))
                .thenReturn(mockResponse(3, 0.0123, 0.6, 30));

        LocalDate start = LocalDate.of(2025, 1, 2);
        LocalDate end = LocalDate.of(2025, 1, 10); // 周五（交易日），便于末窗口截止日 == end
        // 模拟交易日（工作日连续，无周末间隔，便于断言窗口连续 +1 天）
        when(tradingCalendarService.findTradingDays(start, end))
                .thenReturn(List.of(
                        LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 3),
                        LocalDate.of(2025, 1, 6), LocalDate.of(2025, 1, 7),
                        LocalDate.of(2025, 1, 8), LocalDate.of(2025, 1, 9),
                        LocalDate.of(2025, 1, 10)));
        Map<String, Object> out = service().run("CLASSIC_SELECTOR", start, end, 3, 10, 5);

        assertThat(out.get("strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(out.get("windows")).isEqualTo(3);
        assertThat(out.get("holding_day")).isEqualTo(5);

        List<Map<String, Object>> stats = (List<Map<String, Object>>) out.get("window_stats");
        assertThat(stats).hasSize(3);

        // 窗口连续：window1.end + 1 == window2.start
        assertThat(stats.get(0).get("end_date")).isNotEqualTo(stats.get(1).get("start_date"));
        LocalDate w1End = LocalDate.parse((String) stats.get(0).get("end_date"));
        LocalDate w2Start = LocalDate.parse((String) stats.get(1).get("start_date"));
        assertThat(w2Start).isEqualTo(w1End.plusDays(1));

        // 末窗口截止日 == endDate
        assertThat(stats.get(2).get("end_date")).isEqualTo(end.toString());

        // 摘要字段透传
        assertThat(stats.get(0).get("signal_days")).isEqualTo(3);
        assertThat(stats.get(0).get("avg_return")).isEqualTo(0.0123);
        assertThat(stats.get(0).get("win_rate")).isEqualTo(0.6);
        assertThat(stats.get(0).get("observations")).isEqualTo(30);
    }

    @SuppressWarnings("unchecked")
    @Test
    void emptyWindowsProduceZeroStats() {
        when(scoringBacktestService.run(any(ScoringBacktestRequest.class)))
                .thenReturn(ScoringBacktestResponse.builder()
                        .totalTradingDays(0)
                        .holdingPeriodStats(Map.of())
                        .build());

        LocalDate start = LocalDate.of(2025, 1, 2);
        LocalDate end = LocalDate.of(2025, 1, 10);
        Map<String, Object> out = service().run("TREND", start, end, null, null, null);

        assertThat(out.get("windows")).isEqualTo(4); // 默认 4 窗口
        List<Map<String, Object>> stats = (List<Map<String, Object>>) out.get("window_stats");
        assertThat(stats).hasSize(4);
        assertThat(stats.get(0).get("signal_days")).isEqualTo(0);
        assertThat(stats.get(0).get("avg_return")).isEqualTo(0.0);
    }
}
