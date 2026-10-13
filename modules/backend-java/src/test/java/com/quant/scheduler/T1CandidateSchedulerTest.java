package com.quant.scheduler;

import com.quant.repository.MarketDataRepository;
import com.quant.service.T1CandidateService;
import com.quant.service.TradingCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1CandidateScheduler：漏跑自愈的日期解析 + 幂等委托 + 静默失败可观测。
 *
 * <p>核心保护点（DS-R030）：
 * <ol>
 *   <li>日历不覆盖 → 明确 SKIPPED，<b>不猜测日期、不落库</b>；</li>
 *   <li>交易日但行情未入库（含今天未收盘）→ 继续回看，落到最近一个真实有数据的交易日；</li>
 *   <li>委托给 {@code generateCandidates} 时传的必须是**解析出的信号日**，不是今天；</li>
 *   <li>底层抛异常 → 状态记 FAILED 而不是静默丢失。</li>
 * </ol>
 *
 * <p>注意：被测类会**逐日向前试探**，因此默认桩必须覆盖任意日期（这里统一置为非交易日），
 * 再对关心的日期单独覆盖；否则 Mockito 严格桩会因「未声明的日期」直接抛异常。
 */
@ExtendWith(MockitoExtension.class)
class T1CandidateSchedulerTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    @Mock
    private T1CandidateService t1CandidateService;
    @Mock
    private TradingCalendarService tradingCalendarService;
    @Mock
    private MarketDataRepository marketDataRepository;

    @BeforeEach
    void baselineStubs() {
        // 默认：区间内任意一天都**不是**交易日，除非测试显式覆盖。
        lenient().when(tradingCalendarService.coverageWindow())
                .thenReturn(new LocalDate[]{LocalDate.of(2024, 8, 19), LocalDate.of(2026, 10, 12)});
        lenient().when(tradingCalendarService.isTradingDay(any())).thenReturn(false);
        lenient().when(marketDataRepository.findByTradeDate(any())).thenReturn(List.of());
    }

    /** 固定「现在」= 2026-08-20 20:00（上海），便于断言回看行为。 */
    private Clock clockAt(String isoLocalDateTime) {
        return Clock.fixed(Instant.parse(isoLocalDateTime + "Z"), SH);
    }

    private T1CandidateScheduler scheduler(Clock clock, int lookback) {
        T1CandidateScheduler s = new T1CandidateScheduler(
                t1CandidateService, tradingCalendarService, marketDataRepository, clock);
        ReflectionTestUtils.setField(s, "lookbackDays", lookback);
        return s;
    }

    @Test
    void 日历不覆盖该区间时跳过且不委托生成() {
        T1CandidateScheduler s = scheduler(clockAt("2026-08-20T20:00:00"), 10);
        // 日历只覆盖到 2026-08-19，而「今天」是 08-20 ⇒ 窗口内无一天被覆盖
        when(tradingCalendarService.coverageWindow())
                .thenReturn(new LocalDate[]{LocalDate.of(2024, 8, 19), LocalDate.of(2026, 8, 19)});

        Map<String, Object> out = s.runOnce("TEST");

        assertThat(out.get("status")).isEqualTo("SKIPPED");
        assertThat(out.get("reason")).isEqualTo("CALENDAR_UNKNOWN_OR_NO_CLOSED_BAR");
        assertThat(out.get("signal_date")).isNull();
        verify(t1CandidateService, never()).generateCandidates(any(), anyInt());
    }

    @Test
    void 回看跳过无行情交易日并落到最近有数据的交易日() {
        T1CandidateScheduler s = scheduler(clockAt("2026-08-20T20:00:00"), 10);
        // 08-20 是交易日但还没收盘/行情未入库；08-19 是交易日且有行情
        when(tradingCalendarService.isTradingDay(LocalDate.of(2026, 8, 20))).thenReturn(true);
        when(tradingCalendarService.isTradingDay(LocalDate.of(2026, 8, 19))).thenReturn(true);
        when(marketDataRepository.findByTradeDate(LocalDate.of(2026, 8, 19)))
                .thenReturn(List.of(new com.quant.entity.MarketData()));
        when(t1CandidateService.generateCandidates(LocalDate.of(2026, 8, 19), 10))
                .thenReturn(Map.of("status", "OK", "trade_date", "2026-08-19",
                        "returned_count", 3, "threshold_version", "t1_rule_v2",
                        "score_type", "RULE_SCORE"));

        Map<String, Object> out = s.runOnce("TEST");

        assertThat(out.get("status")).isEqualTo("OK");
        assertThat(out.get("signal_date")).isEqualTo("2026-08-19");
        assertThat(out.get("returned_count")).isEqualTo(3);
        // 必须委托的是解析出的 08-19，而不是「今天」08-20
        verify(t1CandidateService).generateCandidates(LocalDate.of(2026, 8, 19), 10);
        verify(t1CandidateService, never()).generateCandidates(LocalDate.of(2026, 8, 20), 10);
    }

    @Test
    void 回看窗口内全是非交易日时跳过而不是猜测日期() {
        T1CandidateScheduler s = scheduler(clockAt("2026-08-20T20:00:00"), 3);

        Map<String, Object> out = s.runOnce("TEST");

        assertThat(out.get("status")).isEqualTo("SKIPPED");
        assertThat(out.get("signal_date")).isNull();
        verify(t1CandidateService, never()).generateCandidates(any(), anyInt());
    }

    @Test
    void 底层抛异常时状态为FAILED并记录原因而不是静默() {
        T1CandidateScheduler s = scheduler(clockAt("2026-08-20T20:00:00"), 10);
        when(tradingCalendarService.isTradingDay(LocalDate.of(2026, 8, 20))).thenReturn(true);
        when(marketDataRepository.findByTradeDate(LocalDate.of(2026, 8, 20)))
                .thenReturn(List.of(new com.quant.entity.MarketData()));
        when(t1CandidateService.generateCandidates(LocalDate.of(2026, 8, 20), 10))
                .thenThrow(new IllegalStateException("boom"));

        Map<String, Object> out = s.runOnce("TEST");

        assertThat(out.get("status")).isEqualTo("FAILED");
        assertThat(out.get("error").toString()).contains("IllegalStateException").contains("boom");
    }

    @Test
    void 状态接口在没有执行过时报告present为false() {
        T1CandidateScheduler s = scheduler(clockAt("2026-08-20T20:00:00"), 10);
        ReflectionTestUtils.setField(s, "enabledFlag", true);
        ReflectionTestUtils.setField(s, "cronExpression", "0 10 15 * * MON-FRI");

        Map<String, Object> st = s.status();

        assertThat(st.get("present")).isEqualTo(false);
        assertThat(st.get("schedule_enabled")).isEqualTo(true);
        assertThat(st.get("cron")).isEqualTo("0 10 15 * * MON-FRI");
        assertThat(st.get("zone")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void 状态接口在执行后暴露上次结果与信号日() {
        T1CandidateScheduler s = scheduler(clockAt("2026-08-20T20:00:00"), 10);
        when(tradingCalendarService.isTradingDay(LocalDate.of(2026, 8, 20))).thenReturn(true);
        when(marketDataRepository.findByTradeDate(LocalDate.of(2026, 8, 20)))
                .thenReturn(List.of(new com.quant.entity.MarketData()));
        when(t1CandidateService.generateCandidates(LocalDate.of(2026, 8, 20), 10))
                .thenReturn(Map.of("status", "NO_ELIGIBLE_CANDIDATE", "trade_date", "2026-08-20"));

        s.runOnce("TEST");
        Map<String, Object> st = s.status();

        assertThat(st.get("present")).isEqualTo(true);
        assertThat(st.get("signal_date")).isEqualTo("2026-08-20");
        assertThat(st.get("status")).isEqualTo("NO_ELIGIBLE_CANDIDATE");
        assertThat(st.get("trigger")).isEqualTo("TEST");
    }
}
