package com.quant.service.scoring;

/**
 * 评分数据完整度标记。
 */
public enum DataQuality {
    FULL,
    PARTIAL_FUNDAMENTALS,
    TECHNICAL_ONLY,
    DEGRADED
}
