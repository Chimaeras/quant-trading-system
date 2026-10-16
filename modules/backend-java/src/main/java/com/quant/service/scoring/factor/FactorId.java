package com.quant.service.scoring.factor;

/**
 * 统一因子注册表（L2 层的因子标识）。
 *
 * <p>设计原则：<b>每个因子只定义一次，且在所有策略里取值相同</b>。
 * 当前实现的问题是同一指标有多套互不相干的标准——例如 RSI 在
 * {@code ScoringRules} 里有 4 套活标准（{@code scoreRsi} 10/8/7/5/3、
 * {@code scoreRsiTrend} 10/9/9/7/6/3/3、{@code scoreRsiOversold}、{@code scoreRsiByStatus}），
 * 且落库的 {@code rsi_status} 用的阈值（70/60/40/30）与计分用的阈值还不一样，
 * 导致同一行数据自相矛盾（RSI=65 既是「强势买入」又只得 7.20 分）。
 *
 * <p>本枚举把「因子是什么」与「某策略给它多少权重、朝哪个方向」彻底分开：
 * 方向与权重属于 {@link StrategyDefinition}，不属于因子本身。
 *
 * <p>{@code minBars} 是<b>该因子自身</b>的最小 K 线需求。当前实现用的是服务级单一常量
 * （{@code MIN_BARS} 在 4 处分别为 30 / 60 / 70 / 260），粒度太粗且互相不一致。
 */
public enum FactorId {

    // ── 动量 / 反转 ──────────────────────────────────────────────────
    MOM_5("5日动量", 6, "近 5 个交易日收益率(%)"),
    MOM_20("20日动量", 21, "近 20 个交易日收益率(%)"),
    MOM_60("60日动量", 61, "近 60 个交易日收益率(%)"),
    MOM_120("120日动量", 121, "近 120 个交易日收益率(%)"),
    REV_5("5日反转", 6, "5 日动量的反向（A 股短期反转效应最强）"),

    // ── 趋势 ────────────────────────────────────────────────────────
    TREND_MA("均线趋势", 60, "均线多头排列强度（ma5/10/20/60 两两比较）"),
    TREND_BIAS("5日乖离", 6, "收盘价相对 ma5 的乖离率(%)"),
    TREND_MACD("MACD柱", 35, "MACD 柱 / 收盘价 × 100（去量纲）"),

    // ── 技术指标 ────────────────────────────────────────────────────
    RSI_14("RSI(14)", 15, "Wilder RSI，中性区间内趋势跟随、两端反转"),
    KDJ_J("KDJ-J", 10, "KDJ 的 J 值"),

    // ── 量能 / 流动性 ───────────────────────────────────────────────
    VOL_RATIO("量比", 31, "近 5 日均量 / 近 30 日均量"),
    VOL_TURNOVER("换手率", 1, "当日换手率(%)；本库 100% 填充，394 行为 0"),
    VOL_PRICE("量价配合", 6, "放量上涨/下跌、缩量上涨/下跌的方向编码"),
    LIQUIDITY_20("20日成交额", 20, "20 日日均成交额（万元），流动性闸同源"),

    // ── 风险 ────────────────────────────────────────────────────────
    VOLATILITY_20("20日波动率", 21, "收益率序列标准差×100"),
    MAXDD_60("60日最大回撤", 60, "60 日区间最大回撤(%)"),

    // ── 估值 / 财务（本库当前全缺，保留以承接未来数据）──────────────────
    VAL_EP("盈利收益率", 1, "1/PE；PE 为 null 则本因子不可用"),
    VAL_BP("账面市值比", 1, "1/PB；PB 为 null 则本因子不可用"),
    QUAL_ROE("ROE", 1, "来自 stock_financial_snapshot（当前 0 行）"),
    QUAL_LEV("资产负债率", 1, "反向：越低越好；来自 stock_financial_snapshot"),
    LIQ_CURRENT("流动比率", 1, "来自 stock_financial_snapshot");

    private final String label;
    private final int minBars;
    private final String description;

    FactorId(String label, int minBars, String description) {
        this.label = label;
        this.minBars = minBars;
        this.description = description;
    }

    public String label() { return label; }

    /** 该因子自身所需的最小 K 线条数。 */
    public int minBars() { return minBars; }

    public String description() { return description; }

    /**
     * 是否属于「基本面/估值」类因子。
     * <p>用于缺数据披露：本库当前 {@code pe_ratio}/{@code pb_ratio} 全 NULL、
     * {@code stock_financial_snapshot} 0 行，故这些因子必然不可用，必须在输出里显式标注，
     * 而不是拿中性常量充数。
     */
    public boolean isFundamental() {
        return this == VAL_EP || this == VAL_BP || this == QUAL_ROE
                || this == QUAL_LEV || this == LIQ_CURRENT;
    }

    /** 是否为「风险/负向自然量纲」因子——不决定方向，仅供展示分类。 */
    public boolean isRisk() {
        return this == VOLATILITY_20 || this == MAXDD_60;
    }
}
