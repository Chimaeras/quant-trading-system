package com.quant.service.execution;

/**
 * 行情用途分类（V2-MP-014）。
 *
 * <p>统一各模块读取行情的目的，决定是否允许 qfq 回退：
 * <ul>
 *   <li>{@link #SIGNAL_FEATURE}：信号/特征计算，可使用 qfq（前复权）或 raw，仅影响模型输入。</li>
 *   <li>{@link #MARK}：净值标记/技术指标展示，可使用 qfq，但响应须带 priceType 标注。</li>
 *   <li>{@link #EXECUTION}：成交定价，只读 market_price_raw；缺 raw 一律 reject，禁止 qfq 兜底。</li>
 * </ul>
 */
public enum ExecutionPricePurpose {

    SIGNAL_FEATURE,
    MARK,
    EXECUTION
}
