package com.quant.service;

import com.quant.repository.MarketDataRepository;
import com.quant.repository.TradingCalendarDayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * R6-01：{@code isTradingDay == false} 有两种语义，必须区分。
 *
 * <p>旧实现把「日历根本不覆盖该日」也当成「休市（非交易日）」，并附注「按交易日历
 * 判定」。日历只更新到 2026-08-19 时，2026-10-12 因此被断言成休市——把「我不知道」
 * 说成了「我确定」，同时掩盖了真实的日历缺口。本组用例锁定三种判定互不混淆：
 * 覆盖区间内为交易日 / 覆盖区间内为非交易日 / 区间外（不可断言）。
 */
class SystemReadinessCalendarVerdictTest {

    private TradingCalendarDayRepository calendarRepository;
    private TradingCalendarService tradingCalendarService;
    private SystemReadinessService service;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        calendarRepository = mock(TradingCalendarDayRepository.class);
        tradingCalendarService = mock(TradingCalendarService.class);
        DataQualityGateService gateService = mock(DataQualityGateService.class);
        service = new SystemReadinessService(
                marketDataRepository, calendarRepository, tradingCalendarService, gateService);

        // readiness() 会聚合 db/时效/门禁三段；这里只关心日历与市场时段两段。
        lenient().when(marketDataRepository.count()).thenReturn(100L);
        lenient().when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.of(today));
        lenient().when(calendarRepository.count()).thenReturn(500L);
        lenient().when(calendarRepository.findMinTradeDate()).thenReturn(Optional.of(today.minusDays(30)));
        lenient().when(calendarRepository.findMaxTradeDate()).thenReturn(Optional.of(today));
        lenient().when(gateService.evaluate(any())).thenReturn(Map.of("gate_status", "PASS"));
    }

    @Test
    void outOfCoverageTodayIsUnknownNotHoliday() {
        // 日历只覆盖到两年前：今天落在区间之外，因此**不可断言**休市。
        LocalDate coverageTo = today.minusYears(2);
        lenient().when(calendarRepository.findMaxTradeDate()).thenReturn(Optional.of(coverageTo));
        lenient().when(tradingCalendarService.coverageWindow())
                .thenReturn(new LocalDate[]{today.minusYears(3), coverageTo});
        lenient().when(tradingCalendarService.isTradingDay(any())).thenReturn(false);

        Map<String, Object> out = service.readiness();
        Map<String, Object> session = cast(out.get("market_session"));
        Map<String, Object> calendar = cast(out.get("calendar"));

        assertThat(session.get("calendar_verdict")).isEqualTo("UNKNOWN");
        assertThat(session.get("phase")).isEqualTo("UNKNOWN");
        assertThat(String.valueOf(session.get("label"))).contains("未覆盖");
        assertThat(String.valueOf(session.get("phase"))).isNotEqualTo("HOLIDAY");
        // 缺口必须可见：调用方要能拿到日历实际覆盖到哪天
        assertThat(session.get("coverage_to")).isEqualTo(coverageTo.toString());
        // 说明文字必须点明「不得据此断言休市」，而不是给出确定结论
        assertThat(String.valueOf(session.get("note"))).contains("不得据此断言休市");

        // 有日历行但没覆盖今天：旧实现这里报 PASS，把缺口一并藏掉
        assertThat(calendar.get("today_calendar_verdict")).isEqualTo("UNKNOWN");
        assertThat(calendar.get("status")).isEqualTo(SystemReadinessService.STATUS_DEGRADED);
    }

    @Test
    void inCoverageNonTradingDayIsHoliday() {
        lenient().when(tradingCalendarService.coverageWindow())
                .thenReturn(new LocalDate[]{today.minusDays(30), today.plusDays(30)});
        lenient().when(tradingCalendarService.isTradingDay(any())).thenReturn(false);

        Map<String, Object> out = service.readiness();
        Map<String, Object> session = cast(out.get("market_session"));
        Map<String, Object> calendar = cast(out.get("calendar"));

        assertThat(session.get("calendar_verdict")).isEqualTo("NON_TRADING");
        assertThat(session.get("phase")).isEqualTo("HOLIDAY");
        assertThat(session.get("today_is_trading_day")).isEqualTo(false);
        assertThat(String.valueOf(session.get("note"))).contains("落在日历覆盖区间内");
        assertThat(calendar.get("status")).isEqualTo(SystemReadinessService.STATUS_PASS);
    }

    @Test
    void inCoverageTradingDayResolvesToSessionPhase() {
        lenient().when(tradingCalendarService.coverageWindow())
                .thenReturn(new LocalDate[]{today.minusDays(30), today.plusDays(30)});
        lenient().when(tradingCalendarService.isTradingDay(any())).thenReturn(true);

        Map<String, Object> session = cast(service.readiness().get("market_session"));

        assertThat(session.get("calendar_verdict")).isEqualTo("TRADING");
        assertThat(session.get("today_is_trading_day")).isEqualTo(true);
        assertThat(String.valueOf(session.get("phase")))
                .isIn("PRE_MARKET", "AUCTION", "PRE_OPEN", "TRADING_AM", "LUNCH", "TRADING_PM", "CLOSED");
    }

    @Test
    void emptyCalendarIsUnknownNotHoliday() {
        lenient().when(calendarRepository.count()).thenReturn(0L);
        lenient().when(tradingCalendarService.coverageWindow()).thenReturn(null);
        lenient().when(tradingCalendarService.isTradingDay(any())).thenReturn(false);

        Map<String, Object> out = service.readiness();
        Map<String, Object> session = cast(out.get("market_session"));

        assertThat(session.get("calendar_verdict")).isEqualTo("UNKNOWN");
        assertThat(session.get("phase")).isEqualTo("UNKNOWN");
        assertThat(session.get("phase")).isNotEqualTo("HOLIDAY");
    }

    @Test
    void calendarQueryFailureIsUnknownNotHoliday() {
        // 日历查询抛异常同样只能 UNKNOWN：查不到 ≠ 非交易日
        lenient().when(tradingCalendarService.coverageWindow())
                .thenThrow(new IllegalStateException("calendar unavailable"));

        Map<String, Object> session = cast(service.readiness().get("market_session"));

        assertThat(session.get("calendar_verdict")).isEqualTo("UNKNOWN");
        assertThat(session.get("phase")).isEqualTo("UNKNOWN");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
