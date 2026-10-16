package com.quant.service.scoring;

/**
 * 可交易性闸门的判定结果。
 *
 * <p>引入原因（V30 批次 A-1）：原 {@code ScoringUtils.isTradableForScoring} 只返回 {@code boolean}，
 * 调用方无法区分「通过」「被 ST 拒绝」「因为缺少 preClose 而无法判定」。而原实现把最后一种情况
 * <b>静默当作通过</b>（{@code ScoringUtils} 旧 :206-208），使得「只有 1 根 K 线」或「前一根收盘价
 * 缺失/非正」的标的绕过涨停与跌停两道闸门而不留任何痕迹。
 *
 * <p>本枚举让「未判定」成为一种<b>可观测的</b>状态，而不是伪装成「通过」。
 *
 * <p>命名约定：{@code *_DEGRADED} 后缀仅由非严格模式（旧布尔重载，{@code requirePreClose=false}）
 * 返回，语义是「放行，但有一道闸门未能生效」；不带后缀的同名项由严格模式返回，语义是「拒绝」。
 */
public enum TradabilityCheck {

    /** 通过全部闸门。 */
    TRADABLE,

    /** 停牌/非活跃状态（{@code stockStatus} 非 null 且非 1）。 */
    INACTIVE_STATUS,

    /** 无 K 线数据。 */
    NO_BARS,

    /** 信号日成交量为 0（停牌代理）。 */
    ZERO_VOLUME,

    /** 名称含 ST，且 {@code excludeST=true}。 */
    ST_NAME,

    /**
     * 名称为 null 或空白，无法确认是否为 ST 股。
     *
     * <p>V30 新增。旧实现让这种情形静默通过 ST 闸，已实测导致 ST 股进入候选池。
     * 生产路径上 5 套打分都会把缺失名称兜底成 {@code ""} 或股票代码，故本项不应出现。
     */
    MISSING_STOCK_NAME,

    /** 信号日收盘涨停，且 {@code excludeLimitUpAtClose=true}。 */
    LIMIT_UP_AT_CLOSE,

    /** 信号日收盘跌停，且 {@code excludeLimitDownAtClose=true}。 */
    LIMIT_DOWN_AT_CLOSE,

    /** 严格模式：缺少股票代码，无法解析涨跌幅限制比例 ⟹ 拒绝。 */
    MISSING_STOCK_CODE,

    /** 严格模式：缺少有效 preClose，无法判定涨跌停 ⟹ 拒绝。 */
    MISSING_PRE_CLOSE,

    /** 非严格模式：缺少股票代码，放行但涨跌停闸未生效（可观测的降级）。 */
    MISSING_STOCK_CODE_DEGRADED,

    /** 非严格模式：缺少有效 preClose，放行但涨跌停闸未生效（旧行为，保留以兼容既有调用方）。 */
    MISSING_PRE_CLOSE_DEGRADED;

    /**
     * 是否为「通过」。仅降级项与 {@link #TRADABLE} 通过；严格模式的 MISSING_* 一律拒绝。
     */
    public boolean isTradable() {
        return this == TRADABLE
                || this == MISSING_PRE_CLOSE_DEGRADED
                || this == MISSING_STOCK_CODE_DEGRADED;
    }

    /**
     * 是否属于「通过了，但有一道闸门未能生效」——调用方应当显式记录，不得当作正常通过。
     */
    public boolean isDegraded() {
        return this == MISSING_PRE_CLOSE_DEGRADED || this == MISSING_STOCK_CODE_DEGRADED;
    }

    /** 供 skip_reason 落库/诊断使用的稳定短码。 */
    public String code() {
        return name();
    }
}
