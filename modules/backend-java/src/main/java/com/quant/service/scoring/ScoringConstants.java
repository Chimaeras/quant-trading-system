package com.quant.service.scoring;

/**
 * 打分系统常量定义
 */
public final class ScoringConstants {

    private ScoringConstants() {}

    // ==================== 技术指标周期 ====================
    public static final int RSI_PERIOD = 14;
    public static final int MACD_FAST = 12;
    public static final int MACD_SLOW = 26;
    public static final int MACD_SIGNAL = 9;
    public static final int KDJ_PERIOD = 9;
    public static final int MA5_PERIOD = 5;
    public static final int MA10_PERIOD = 10;
    public static final int MA20_PERIOD = 20;
    public static final int MA60_PERIOD = 60;

    // ==================== 最小K线数要求 ====================
    public static final int MIN_BARS_TECHNICAL = 60;
    public static final int MIN_BARS_COMPREHENSIVE = 60;
    public static final int MIN_BARS_OVERSOLD = 260;
    public static final int MIN_BARS_TREND = 26;

    // ==================== 量价阈值 ====================
    public static final double VOLUME_SHRINK_RATIO = 0.7;
    public static final double VOLUME_HEAVY_RATIO = 1.5;
    public static final double MA_SUPPORT_TOLERANCE = 0.02;

    // ==================== RSI阈值 ====================
    public static final double RSI_OVERSOLD = 30.0;
    public static final double RSI_OVERBOUGHT = 70.0;

    // ==================== 默认权重 ====================
    public static final double DEFAULT_TECH_WEIGHT = 0.33;
    public static final double DEFAULT_FUND_WEIGHT = 0.34;
    public static final double DEFAULT_SENTIMENT_WEIGHT = 0.33;

    // ==================== 因子版本 ====================
    public static final String VERSION_TREND = "trend_signal_v2";
    public static final String VERSION_OVERSOLD = "oversold_rebound_v2_confirm";
    public static final String VERSION_COMPREHENSIVE = "comprehensive_v2_fundamentals";
    public static final String VERSION_MULTI_DIM = "multi_dim_v2_normalized";

    // ==================== 默认回溯天数 ====================
    public static final int DEFAULT_LOOKBACK_DAYS = 520;

    // ==================== P0: 换手率阈值 ====================
    public static final double TURNOVER_COLD = 1.0;
    public static final double TURNOVER_MILD = 3.0;
    public static final double TURNOVER_ACTIVE = 8.0;
    public static final double TURNOVER_HOT = 15.0;

    // ==================== P1: 均线粘合阈值 ====================
    public static final double MA_CONVERGENCE_TIGHT = 0.02;
    public static final double MA_CONVERGENCE_LOOSE = 0.08;

    // ==================== P1: 日内振幅阈值 ====================
    public static final double AMPLITUDE_LOW = 2.0;
    public static final double AMPLITUDE_HIGH = 7.0;
    public static final double AMPLITUDE_EXTREME = 10.0;

    // ==================== P1: 连续量能天数阈值 ====================
    public static final int CONSECUTIVE_VOL_STRONG_SIGNAL = 3;

    // ==================== P2: 波动率收敛阈值 ====================
    public static final double VOL_CONVERGENCE_THRESHOLD = -0.2;
    public static final double VOL_EXPANSION_THRESHOLD = 0.3;
}