package com.quant.service;

import com.quant.repository.MarketDataRepository;
import com.quant.repository.TradingCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统就绪度聚合（V2-MP-028）：顶部状态只取 {@code health}（db/calendar/data freshness/data gate），
 * 绝不从本机时钟推断健康；市场时段作为独立信息字段展示，其「是否交易日」以交易日历表为准。
 *
 * <p>状态语义（前端可判定，非仅换色）：
 * <ul>
 *   <li>PASS —— 各基座均就绪；</li>
 *   <li>DEGRADED —— 任一基座降级（如行情迟到、日历未初始化、数据门禁 DEGRADED）；</li>
 *   <li>BLOCKED —— 任一基座不可用（DB 断开、无行情、门禁 BLOCKED）。</li>
 * </ul>
 * 拒绝「把本机时间当系统健康」：{@code status} 与 clock 无关；{@code market_session} 仅为告知性时段。
 */
@Service
@RequiredArgsConstructor
public class SystemReadinessService {

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_BLOCKED = "BLOCKED";

    private final MarketDataRepository marketDataRepository;
    private final TradingCalendarDayRepository tradingCalendarDayRepository;
    private final TradingCalendarService tradingCalendarService;
    private final DataQualityGateService dataQualityGateService;

    /** 聚合就绪度（只读，不触发任何写入）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> readiness() {
        LocalDate today = LocalDate.now();

        Map<String, Object> db = checkDbHealth();
        Map<String, Object> calendar = checkCalendar(today);
        Map<String, Object> freshness = checkDataFreshness();
        Map<String, Object> gate = checkDataGate();
        Map<String, Object> session = buildMarketSession(today);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", aggregate(db, calendar, freshness, gate));
        out.put("db_health", db);
        out.put("calendar", calendar);
        out.put("data_freshness", freshness);
        out.put("data_gate", gate);
        out.put("market_session", session);
        out.put("checked_at", today.toString());
        out.put("server_time", LocalTime.now().toString());
        out.put("note", "status 由 db/日历/行情时效/数据门禁聚合，不依赖本机时间；market_session 仅为告知性市场时段（交易日以日历表为准）。"
                + "列表页与顶部状态不得把本机时钟当系统健康。");
        return out;
    }

    /** DB 健康：任一核心表可计数即 PASS；异常即 BLOCKED。 */
    private Map<String, Object> checkDbHealth() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            long rows = marketDataRepository.count();
            m.put("status", STATUS_PASS);
            m.put("detail", "行情库可读（market_data 行数=" + rows + "）");
        } catch (Exception e) {
            m.put("status", STATUS_BLOCKED);
            m.put("detail", "数据库不可达：" + safeMsg(e));
        }
        return m;
    }

    /** 日历对某日的判定：已知交易日 / 已知非交易日 / 日历未覆盖（不可断言）。 */
    private enum DayVerdict { TRADING, NON_TRADING, UNKNOWN }

    /**
     * R6-01：把 {@code isTradingDay == false} 拆成两种语义。
     *
     * <p>旧实现把「日历根本不覆盖该日」与「已知为非交易日」都当成休市。日历只更新到
     * 2026-08-19 时，2026-10-12 因此被断言成「休市（非交易日）——按交易日历判定」，
     * 这是**虚假断言**：它掩盖了真实的日历缺口，把「我不知道」说成了「我确定休市」。
     *
     * <p>只有落在 {@link TradingCalendarService#coverageWindow()} 之内，false 才是
     * 可断言的「非交易日」。日历为空、不可用、或该日在区间外，一律 UNKNOWN。
     */
    private DayVerdict verdictFor(LocalDate day) {
        if (day == null || tradingCalendarService == null) {
            return DayVerdict.UNKNOWN;
        }
        if (tradingCalendarDayRepository.count() == 0) {
            return DayVerdict.UNKNOWN;
        }
        try {
            LocalDate[] window = tradingCalendarService.coverageWindow();
            if (window == null || day.isBefore(window[0]) || day.isAfter(window[1])) {
                return DayVerdict.UNKNOWN;
            }
            return tradingCalendarService.isTradingDay(day)
                    ? DayVerdict.TRADING : DayVerdict.NON_TRADING;
        } catch (RuntimeException exc) {
            // 日历查询失败同样只能 UNKNOWN：查不到 ≠ 非交易日。
            return DayVerdict.UNKNOWN;
        }
    }

    /** 日历：存在交易日历行即 PASS；未初始化或未覆盖今日即 DEGRADED（可初始化，非致命故障）。 */
    private Map<String, Object> checkCalendar(LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        long rows = tradingCalendarDayRepository.count();
        m.put("total_rows", rows);
        DayVerdict verdict = verdictFor(today);
        m.put("today_is_trading_day", verdict == DayVerdict.TRADING);
        m.put("today_calendar_verdict", verdict.name());
        if (rows == 0) {
            m.put("status", STATUS_DEGRADED);
            m.put("detail", "交易日历未初始化（可经 /api/data/trading-calendar/init 初始化）");
            return m;
        }
        tradingCalendarDayRepository.findMinTradeDate().ifPresent(d -> m.put("earliest_date", d.toString()));
        tradingCalendarDayRepository.findMaxTradeDate().ifPresent(d -> m.put("latest_date", d.toString()));
        if (verdict == DayVerdict.UNKNOWN) {
            // R6-01：有日历行但没覆盖今天。旧实现这里报 PASS，同时把今天说成休市，
            // 于是日历缺口既没被报出来、又被伪装成确定结论。
            m.put("status", STATUS_DEGRADED);
            m.put("detail", "交易日历未覆盖今日 " + today + "（现有区间 "
                    + m.getOrDefault("earliest_date", "?") + " ~ "
                    + m.getOrDefault("latest_date", "?") + "），无法判定开市与否");
            return m;
        }
        m.put("status", STATUS_PASS);
        m.put("detail", "交易日历已启用（" + rows + " 个交易日），今日结论=" + verdict.name());
        return m;
    }

    /**
     * 行情时效：以日历最新交易日为「期望最新日」，迟到即 DEGRADED（节假日感知，不做固定天数阈值）。
     */
    private Map<String, Object> checkDataFreshness() {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate latestMarket = marketDataRepository.findMaxTradeDate().orElse(null);
        if (latestMarket == null) {
            m.put("status", STATUS_BLOCKED);
            m.put("detail", "无行情数据（findMaxTradeDate 为空）");
            return m;
        }
        m.put("latest_market_date", latestMarket.toString());
        boolean calendarReady = tradingCalendarDayRepository.count() > 0;
        if (!calendarReady) {
            m.put("status", STATUS_DEGRADED);
            m.put("detail", "行情最新 " + latestMarket + "，但日历未初始化，无法判定时效");
            return m;
        }
        LocalDate expectedLatest = tradingCalendarDayRepository.findMaxTradeDate().orElse(null);
        if (expectedLatest == null) {
            m.put("status", STATUS_DEGRADED);
            m.put("detail", "行情最新 " + latestMarket + "，日历无交易日可比对");
            return m;
        }
        m.put("expected_latest_trade_date", expectedLatest.toString());
        if (latestMarket.isBefore(expectedLatest)) {
            m.put("status", STATUS_DEGRADED);
            m.put("detail", "行情迟到：最新 " + latestMarket + "，日历最新交易日 " + expectedLatest);
        } else {
            m.put("status", STATUS_PASS);
            m.put("detail", "行情已到最新交易日 " + latestMarket);
        }
        return m;
    }

    /** 数据门禁（RULE_SCORE 视角）直接复用 MP-006 gate，不重复造状态。 */
    private Map<String, Object> checkDataGate() {
        Map<String, Object> gate = dataQualityGateService.evaluate(
                DataQualityGateService.GateRequest.of(DataQualityGateService.PURPOSE_RULE_SCORE, null));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", String.valueOf(gate.getOrDefault("gate_status", "UNKNOWN")));
        m.put("purpose", gate.get("purpose"));
        m.put("decision_id", gate.get("decision_id"));
        m.put("threshold_version", gate.get("threshold_version"));
        m.put("detail", "数据质量门禁 RULE_SCORE：" + gate.getOrDefault("gate_status", "UNKNOWN"));
        return m;
    }

    /**
     * 市场时段（告知性）：交易日以日历表为准，时钟仅用于划分当日交易阶段。
     * 这不等同于系统健康；若日历无该日则明确标记非交易日/未知。
     *
     * <p><b>R6-01（2026-10-13 修正）</b>：旧实现对「日历不覆盖该日」与「已知非交易日」
     * 都输出 {@code phase=HOLIDAY / "休市（非交易日）"}，把未知伪装成确定结论。
     * 现在区间外输出 {@code phase=UNKNOWN}，并附上日历覆盖区间，使缺口可见。
     */
    private Map<String, Object> buildMarketSession(LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        DayVerdict verdict = verdictFor(today);
        m.put("today_is_trading_day", verdict == DayVerdict.TRADING);
        m.put("calendar_verdict", verdict.name());
        tradingCalendarDayRepository.findMinTradeDate().ifPresent(d -> m.put("coverage_from", d.toString()));
        tradingCalendarDayRepository.findMaxTradeDate().ifPresent(d -> m.put("coverage_to", d.toString()));
        if (verdict == DayVerdict.UNKNOWN) {
            // 日历不覆盖该日：既不能断言开市，也不能断言休市。
            m.put("phase", "UNKNOWN");
            m.put("label", "未知（交易日历未覆盖该日）");
            m.put("note", "该日不在日历覆盖区间内，无法判定开市与否；不得据此断言休市，"
                    + "请先更新交易日历");
            return m;
        }
        if (verdict == DayVerdict.NON_TRADING) {
            m.put("phase", "HOLIDAY");
            m.put("label", "休市（非交易日）");
            m.put("note", "该日落在日历覆盖区间内且标注为非交易日");
            return m;
        }
        LocalTime now = LocalTime.now();
        String phase;
        String label;
        if (now.isBefore(LocalTime.of(9, 15))) {
            phase = "PRE_MARKET"; label = "盘前";
        } else if (now.isBefore(LocalTime.of(9, 25))) {
            phase = "AUCTION"; label = "集合竞价";
        } else if (now.isBefore(LocalTime.of(9, 30))) {
            phase = "PRE_OPEN"; label = "开盘准备";
        } else if (now.isBefore(LocalTime.of(11, 30))) {
            phase = "TRADING_AM"; label = "交易中（上午）";
        } else if (now.isBefore(LocalTime.of(13, 0))) {
            phase = "LUNCH"; label = "午间休市";
        } else if (now.isBefore(LocalTime.of(15, 0))) {
            phase = "TRADING_PM"; label = "交易中（下午）";
        } else {
            phase = "CLOSED"; label = "已收盘";
        }
        m.put("phase", phase);
        m.put("label", label);
        m.put("session_window", "09:15-11:30 / 13:00-15:00（沪市）");
        m.put("note", "时段仅作展示，不参与系统健康判定");
        return m;
    }

    private static String aggregate(Map<String, Object>... parts) {
        for (Map<String, Object> p : parts) {
            if (STATUS_BLOCKED.equals(p.get("status"))) {
                return STATUS_BLOCKED;
            }
        }
        for (Map<String, Object> p : parts) {
            if (STATUS_DEGRADED.equals(p.get("status"))) {
                return STATUS_DEGRADED;
            }
        }
        return STATUS_PASS;
    }

    private static String safeMsg(Exception e) {
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }
}
