package com.quant.service.scoring;

import com.quant.config.ScoringProperties;

/**
 * 将 Spring 配置桥接到静态工具类（ScoringRules 等）。
 */
public class ScoringPropertiesBridge {

    private static volatile ScoringProperties PROPERTIES;

    public ScoringPropertiesBridge(ScoringProperties properties) {
        PROPERTIES = properties;
    }

    public static ScoringProperties get() {
        return PROPERTIES;
    }
}
