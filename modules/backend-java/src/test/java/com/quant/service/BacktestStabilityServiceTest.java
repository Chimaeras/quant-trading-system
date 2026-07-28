package com.quant.service;

import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.dto.ScoringBacktestResponse.PeriodStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestStabilityServiceTest {

    @Mock
    private ScoringBacktestService scoringBacktestService;

    private BacktestStabilityService service() {
        return new BacktestStabilityService(scoringBacktestService);
    }

    private static ScoringBacktestResponse resp(int signalDays, double avgReturn, double winRate, int obs) {
        PeriodStat stat = PeriodStat.builder()
                .avgReturn(avgReturn).winRate(winRate).totalObservations(obs)
                .medianReturn(0).maxReturn(0).minReturn(0).avgWin(0).avgLoss(0).profitLossRatio(0)
                .build();
        return ScoringBacktestResponse.builder()
                .totalTradingDays(signalDays)
                .holdingPeriodStats(Map.of(5, stat))
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void analyzeSplitsAndComparesHalves() {
        // 前半段 avg=0.02, 后半段 avg=-0.01
        when(scoringBacktestService.run(any(ScoringBacktestRequest.class)))
                .thenReturn(resp(10, 0.02, 0.6, 100))   // first call (full)
                .thenReturn(resp(5, 0.02, 0.6, 50))     // first half
                .thenReturn(resp(5, -0.01, 0.4, 50));   // second half

        LocalDate start = LocalDate.of(2025, 1, 2);
        LocalDate end = LocalDate.of(2025, 2, 2);
        Map<String, Object> out = service().analyze("CLASSIC_SELECTOR", start, end, 10, 5);

        assertThat(out.get("strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(out.get("holding_day")).isEqualTo(5);

        Map<String, Object> first = (Map<String, Object>) out.get("first_half");
        Map<String, Object> second = (Map<String, Object>) out.get("second_half");
        assertThat(first.get("avg_return")).isEqualTo(0.02);
        assertThat(second.get("avg_return")).isEqualTo(-0.01);

        String verdict = (String) out.get("verdict");
        assertThat(verdict).contains("衰退");
    }

    @SuppressWarnings("unchecked")
    @Test
    void analyzeReturnsInsufficientWhenNoObs() {
        when(scoringBacktestService.run(any(ScoringBacktestRequest.class)))
                .thenReturn(ScoringBacktestResponse.builder()
                        .totalTradingDays(0)
                        .holdingPeriodStats(Map.of())
                        .build());

        LocalDate start = LocalDate.of(2025, 1, 2);
        LocalDate end = LocalDate.of(2025, 1, 20);
        Map<String, Object> out = service().analyze("TREND", start, end, null, null);

        Map<String, Object> first = (Map<String, Object>) out.get("first_half");
        assertThat(first.get("observations")).isEqualTo(0);
        assertThat((String) out.get("verdict")).contains("不足");
    }
}
