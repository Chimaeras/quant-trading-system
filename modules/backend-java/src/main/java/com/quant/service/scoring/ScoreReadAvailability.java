package com.quant.service.scoring;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R3-02：打分读取接口的可用性标记。
 *
 * <p>这些接口过去把旧数据与空数据**直接返回**，调用方无从区分三种完全不同的情况。
 * 实测（Codex 数据库 + 真实 GET 双向确认）：
 * <pre>
 *   trend    表 3345 行，最新只到 2026-04-07（半年前）→ GET 仍以 trade_date=2026-04-07 返回
 *   multi_dim 表 0 行                                → GET 返回 trade_date=""、total=0
 * </pre>
 * 前者把「半年前的结果」当最新结果交付，后者把「没有结果」与「结果为空页」混为一谈。
 *
 * <p>本类把判断集中在同一处，输出可直接并入响应体的字段，使三种情况显式可辨。
 * 注意：本类**不**改变取数逻辑，只如实标注。
 */
public final class ScoreReadAvailability {

    /** 有数据，且不落后于库内最新行情日、也不落后于调用方请求的日期。 */
    public static final String STATUS_OK = "OK";
    /** 有数据，但落后于库内最新行情日，或落后于调用方请求的日期（被静默降级）。 */
    public static final String STATUS_STALE = "STALE";
    /** 该打分类型/因子版本**没有任何**数据行。 */
    public static final String STATUS_EMPTY = "EMPTY";

    private ScoreReadAvailability() {
    }

    /**
     * 生成可用性字段。
     *
     * @param effective 实际取到的打分日期；无数据时传 {@code null}
     * @param requested 调用方请求的日期；未指定时传 {@code null}
     * @param expected  库内最新行情日，作为「本应到哪天」的参照；不可知时传 {@code null}
     * @return 可直接 {@code putAll} 进响应体的字段
     */
    public static Map<String, Object> describe(LocalDate effective, LocalDate requested, LocalDate expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested_trade_date", requested == null ? "" : requested.toString());
        m.put("effective_trade_date", effective == null ? "" : effective.toString());
        m.put("expected_trade_date", expected == null ? "" : expected.toString());
        m.put("as_of", LocalDate.now().toString());
        if (effective == null) {
            // 没有任何数据行：这是「无结果」，不是「结果为空页」。
            m.put("data_status", STATUS_EMPTY);
            m.put("staleness_days", null);
            m.put("downgraded", false);
            return m;
        }
        // 调用方要了某一天却拿到更早的一天 = 静默降级，即使绝对日期不算旧也要标出来。
        boolean downgraded = requested != null && effective.isBefore(requested);
        boolean behindMarket = expected != null && expected.isAfter(effective);
        m.put("downgraded", downgraded);
        m.put("staleness_days", behindMarket ? ChronoUnit.DAYS.between(effective, expected) : 0L);
        m.put("data_status", (downgraded || behindMarket) ? STATUS_STALE : STATUS_OK);
        return m;
    }
}
