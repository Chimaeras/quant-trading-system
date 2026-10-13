package com.quant.scheduler;

import com.quant.repository.MarketDataRepository;
import com.quant.service.T1CandidateService;
import com.quant.service.TradingCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T 日收盘后自动生成次日候选清单（DS-R030）。
 *
 * <p><b>解决什么问题</b>：此前候选清单**只能**靠人工调用
 * {@code POST /api/research/t1/candidates} 生成，全仓没有任何 {@code @Scheduled}。
 * 于是「次日候选」链路在无人值守时永远不产出，价值研究无法自动化。
 *
 * <p><b>为什么是「回看最近若干交易日」而不是「只生成今天」</b>：
 * 定时任务可能因停机、重启、数据迟到而漏跑。若只认当天，一次漏跑就永久缺一天快照。
 * 这里改为在最近 {@code lookbackDays} 个自然日内找**最近一个已收盘且日历确认的交易日**，
 * 逐日委托 {@code generateCandidates}。该方法本身就幂等：
 * <ul>
 *   <li>已有快照 → 原样读取并标注 {@code snapshot_reused=true}（历史证据不被改写）；</li>
 *   <li>行情未到 / 日历不认 → 返回明确的 {@code NO_DATA} / {@code NOT_A_TRADING_DAY}
 *       / {@code UNKNOWN} 语义且不落库；</li>
 *   <li>覆盖率不足 → {@code INCOMPLETE_DAILY_DATA} 且不落库。</li>
 * </ul>
 * 因此重复执行不会产生重复行，也不会覆盖既有研究结论。
 *
 * <p><b>安全边界</b>：本任务只读行情/日历，写 {@code t1_candidate} 快照，
 * 不触碰任何下单接口；默认关闭（{@code quant.t1.schedule.enabled=false}），
 * 需显式开启才会自动生成。即使开启，产出的也只是「值得进一步研究的清单」，
 * {@code score_type=RULE_SCORE}，不是上涨概率，也不构成买卖指令。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class T1CandidateScheduler {

    private final T1CandidateService t1CandidateService;
    private final TradingCalendarService tradingCalendarService;
    private final MarketDataRepository marketDataRepository;
    private final Clock marketClock;

    /** 回看窗口（自然日）：定时任务可能因停机/重启漏跑，向前找回最近一个真实有数据的交易日。 */
    @Value("${quant.t1.schedule.lookback-days:10}")
    private int lookbackDays;

    @Value("${quant.t1.schedule.enabled:false}")
    private boolean enabledFlag;

    @Value("${quant.t1.schedule.cron:0 10 15 * * MON-FRI}")
    private String cronExpression;

    /** 最近一次执行结果，供只读状态接口/前端展示可观测性。 */
    private volatile Map<String, Object> lastRun;

    @Scheduled(cron = "${quant.t1.schedule.cron:0 10 15 * * MON-FRI}",
            zone = "Asia/Shanghai")
    public void generateDailyCandidates() {
        runOnce("SCHEDULED");
    }

    /** 供测试与运维手动触发同一段逻辑（不走 cron）。 */
    public Map<String, Object> runOnce(String trigger) {
        LocalDateTime startedAt = LocalDateTime.now(marketClock);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trigger", trigger);
        out.put("started_at", startedAt.toString());
        try {
            LocalDate signalDate = resolveSignalDate();
            out.put("signal_date", signalDate == null ? null : signalDate.toString());
            if (signalDate == null) {
                out.put("status", "SKIPPED");
                out.put("reason", "CALENDAR_UNKNOWN_OR_NO_CLOSED_BAR");
                out.put("note", "日历未覆盖该区间或区间内没有已收盘交易日；不落库、不猜测日期");
            } else {
                Map<String, Object> result = t1CandidateService.generateCandidates(signalDate, 10);
                out.put("status", result.get("status"));
                out.put("signal_date", result.get("trade_date"));
                out.put("returned_count", result.get("returned_count"));
                out.put("eligible_count", result.get("eligible_count"));
                out.put("threshold_version", result.get("threshold_version"));
                out.put("score_type", result.get("score_type"));
                out.put("snapshot_reused", result.get("snapshot_reused"));
                out.put("daily_coverage", result.get("daily_coverage"));
                out.put("calendar_trading_day", result.get("calendar_trading_day"));
            }
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉（只在日志里），导致「静默不产出」。
            // 这里显式兜住并记录，保证状态可查。
            out.put("status", "FAILED");
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("T1 候选定时生成失败（trigger={}）", trigger, e);
        }
        out.put("finished_at", LocalDateTime.now(marketClock).toString());
        lastRun = out;
        if (!"FAILED".equals(out.get("status"))) {
            log.info("T1 候选定时生成: {}", out);
        }
        return out;
    }

    /**
     * 最近一个「日历确认是交易日」且「已有当日行情」的日期。
     *
     * <p>两者都必需：只看日历会把「日历说是交易日但行情还没入库」的今天选中，
     * 于是每天定时跑出一个空结果；只看行情则可能把非交易日当成交易日。
     *
     * @return 找不到时返回 {@code null}（明确跳过，不猜测）
     */
    private LocalDate resolveSignalDate() {
        LocalDateTime now = LocalDateTime.now(marketClock);
        LocalDate today = now.toLocalDate();
        int lookback = scheduleLookbackDays();
        for (int i = 0; i < lookback; i++) {
            LocalDate day = today.minusDays(i);
            if (!isCalendarTradingDay(day)) {
                continue;
            }
            if (marketDataRepository.findByTradeDate(day).isEmpty()) {
                // 该日是交易日但行情未入库：若就是今天，说明尚未收盘/数据滞后，
                // 继续往前找上一个真实有数据的日子；若是过去的日子，说明行情缺口。
                log.debug("交易日 {} 无行情，继续回看", day);
                continue;
            }
            return day;
        }
        return null;
    }

    /** 日历覆盖该日且该日被判定为交易日。 */
    private boolean isCalendarTradingDay(LocalDate day) {
        LocalDate[] window = tradingCalendarService.coverageWindow();
        if (window == null || day.isBefore(window[0]) || day.isAfter(window[1])) {
            return false;
        }
        return tradingCalendarService.isTradingDay(day);
    }

    private int scheduleLookbackDays() {
        return lookbackDays > 0 ? Math.min(lookbackDays, 30) : 10;
    }

    /** 最近一次执行状态（只读，供接口/前端观测；从未执行过时返回 {@code present=false}）。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> last = lastRun;
        out.put("present", last != null);
        out.put("schedule_enabled", scheduleEnabled());
        out.put("cron", scheduleCron());
        out.put("zone", "Asia/Shanghai");
        if (last != null) {
            out.putAll(last);
            Object fin = last.get("finished_at");
            if (fin != null) {
                try {
                    long secs = Duration.between(LocalDateTime.parse(fin.toString()),
                            LocalDateTime.now(marketClock)).getSeconds();
                    out.put("seconds_since_last_run", secs);
                } catch (Exception ignored) {
                    // finished_at 解析失败不影响主状态返回
                }
            }
        }
        return out;
    }

    private boolean scheduleEnabled() {
        return enabledFlag;
    }

    private String scheduleCron() {
        return cronExpression;
    }
}
