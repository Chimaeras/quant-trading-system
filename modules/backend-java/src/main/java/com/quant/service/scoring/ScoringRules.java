package com.quant.service.scoring;

/**
 * 打分规则统一配置
 * 各维度评分标准、阈值、权重统一定义
 */
public final class ScoringRules {

    private ScoringRules() {}

    // ==================== 趋势打分规则 (30分) ====================

    public static double scoreTrend(String trendStatus) {
        return switch (trendStatus) {
            case "强势多头" -> 30;
            case "多头排列" -> 26;
            case "弱势多头" -> 18;
            case "盘整" -> 12;
            case "弱势空头" -> 8;
            case "空头排列" -> 4;
            case "强势空头" -> 0;
            default -> 12;
        };
    }

    public static int trendStrength(String trendStatus) {
        return switch (trendStatus) {
            case "强势多头" -> 90;
            case "多头排列" -> 75;
            case "弱势多头" -> 60;
            case "盘整" -> 50;
            case "弱势空头" -> 40;
            case "空头排列" -> 30;
            case "强势空头" -> 20;
            default -> 50;
        };
    }

    // ==================== 乖离率打分规则 (20分) ====================

    public static double scoreBias(double bias, String trendStatus, double threshold) {
        boolean strongBull = "强势多头".equals(trendStatus) && trendStrength(trendStatus) >= 70;
        // 强势多头时容忍更高乖离：threshold * 1.5
        double maxTolerable = strongBull ? threshold * 1.5 : threshold;

        if (Double.isNaN(bias)) bias = 0;

        // 负乖离（价格低于MA5）：偏差越大分越低
        if (bias < 0) {
            if (bias > -3) return 20;
            if (bias > -5) return 16;
            return 8;
        }

        // 正乖离（价格高于MA5）
        if (bias < 2) return 18;                          // 轻微乖离，最佳
        if (bias <= threshold) return 14;                  // 在标准阈值内
        if (bias <= maxTolerable) return strongBull ? 10 : 4;  // 超标准但未超强势上限
        return 4;                                          // 乖离过大，回调风险
    }

    // ==================== 成交量打分规则 (15分) ====================

    /**
     * 趋势量价关系打分（放量上涨/缩量回调等）
     */
    public static double scoreVolume(String volumeStatus) {
        return switch (volumeStatus) {
            case "缩量回调" -> 15;
            case "放量上涨" -> 12;
            case "量能正常" -> 10;
            case "缩量上涨" -> 6;
            case "放量下跌" -> 0;
            default -> 10;
        };
    }

    /**
     * 技术面量价关系打分（量价齐升/量价背离/量能正常），与 classifyVolumePrice 返回的状态对应。
     */
    public static double scoreVolumePrice(String vpStatus) {
        return switch (vpStatus) {
            case "量价齐升" -> 10;
            case "量能正常" -> 3;
            case "量价背离" -> -7;
            default -> 3;
        };
    }

    // ==================== MACD打分规则 (15分) ====================

    public static double scoreMacd(String macdStatus) {
        return switch (macdStatus) {
            case "零轴上金叉" -> 15;
            case "金叉" -> 12;
            case "上穿零轴" -> 10;
            case "多头" -> 8;
            case "空头" -> 2;
            case "下穿零轴" -> 0;
            case "死叉" -> 0;
            default -> 8;
        };
    }

    // ==================== RSI打分规则 (10分) ====================

    /**
     * RSI 通用评分（适用于综合/超跌反弹等场景）
     */
    public static double scoreRsi(double rsi) {
        if (rsi < 30) return 10;       // 超卖，可能反弹
        if (rsi <= 40) return 8;
        if (rsi <= 60) return 7;
        if (rsi <= 70) return 5;
        return 3;                       // 超买
    }

    /**
     * RSI 趋势策略评分：奖励趋势中的健康区间（40-65），平滑过渡避免断层。
     * 趋势策略中 RSI<30 意味着弱势，不应给满分（超卖可以更超卖）。
     */
    public static double scoreRsiTrend(double rsi) {
        if (rsi >= 40 && rsi <= 60) return 10;   // 趋势最佳区间
        if (rsi > 60 && rsi <= 68) return 9;      // 偏强但健康
        if (rsi > 35 && rsi < 40) return 9;       // 略弱但可接受
        if (rsi > 68 && rsi <= 75) return 7;      // 偏强/轻度超买
        if (rsi >= 30 && rsi <= 35) return 6;     // 偏弱
        if (rsi > 75) return 3;                   // 严重超买
        return 3;                                  // RSI<30：弱势，谨慎
    }

    /**
     * @deprecated 使用 {@link #scoreRsi(double)} 或 {@link #scoreRsiTrend(double)} 代替。
     * 基于状态字符串的 RSI 评分与连续值评分不一致，将在后续版本移除。
     */
    @Deprecated
    public static double scoreRsiByStatus(String rsiStatus) {
        return switch (rsiStatus) {
            case "超卖" -> 10;
            case "强势买入" -> 8;
            case "中性" -> 5;
            case "弱势" -> 3;
            case "超买" -> 0;
            default -> 5;
        };
    }

    // ==================== 均线排列打分规则 (10分) ====================

    public static double scoreMaArrangement(String maStatus) {
        return switch (maStatus) {
            case "多头排列" -> 10;
            case "弱势多头" -> 7;
            case "中性排列" -> 5;
            case "空头排列" -> 2;
            default -> 5;
        };
    }

    // ==================== KDJ打分规则 ====================

    /**
     * KDJ 状态打分。注意：K高位向下 / K下穿D 返回负分，在组合打分中权重一般较小(≤0.1)，
     * 单个 KDJ 负分影响有限，不会显著拉低总分。
     */
    public static double scoreKdj(String kdjStatus) {
        return switch (kdjStatus) {
            case "K低位向上" -> 12;
            case "K高位向下" -> -12;
            case "K上穿D" -> 8;
            case "K下穿D" -> -8;
            default -> 0;
        };
    }

    // ==================== 基本面打分规则 ====================

    /**
     * PE估值打分 (0-15分)
     */
    public static double scorePE(Double pe) {
        if (pe == null) return 7.5;
        if (pe < 15) return 15;
        if (pe < 25) return 12;
        if (pe < 40) return 8;
        if (pe < 60) return 5;
        return 3;
    }

    /**
     * PB估值打分 (0-15分)
     */
    public static double scorePB(Double pb) {
        if (pb == null) return 7.5;
        if (pb < 1) return 15;
        if (pb < 1.5) return 12;
        if (pb < 2.5) return 8;
        return 5;
    }

    /**
     * ROE盈利能力打分 (0-15分)
     */
    public static double scoreROE(Double roe) {
        if (roe == null) return 7.5;
        if (roe > 20) return 15;
        if (roe > 15) return 12;
        if (roe > 10) return 9;
        if (roe > 5) return 6;
        return 3;
    }

    /**
     * 资产负债率打分 (0-10分)
     */
    public static double scoreDebtRatio(Double debtRatio) {
        if (debtRatio == null) return 5;
        if (debtRatio < 40) return 10;
        if (debtRatio < 60) return 7;
        if (debtRatio < 70) return 4;
        return 2;
    }

    /**
     * 流动比率打分 (0-10分)
     */
    public static double scoreCurrentRatio(Double currentRatio) {
        if (currentRatio == null) return 5;
        if (currentRatio > 2) return 10;
        if (currentRatio > 1.5) return 8;
        if (currentRatio > 1) return 6;
        return 3;
    }

    // ==================== 超跌反弹打分规则 ====================

    /**
     * 60日收益率打分（超跌反弹策略）
     */
    public static int scoreRet60(double ret60Pct) {
        if (ret60Pct <= -20) return 15;
        if (ret60Pct <= -10) return 10;
        if (ret60Pct <= -5) return 5;
        return 0;
    }

    /**
     * 120日收益率打分（超跌反弹策略）
     */
    public static int scoreRet120(double ret120Pct) {
        if (ret120Pct <= -30) return 5;
        if (ret120Pct <= -20) return 3;
        if (ret120Pct <= -10) return 1;
        return 0;
    }

    /**
     * 周K阴线周数打分（超跌反弹策略）
     */
    public static int scoreBearWeeks(int weeks) {
        if (weeks >= 5) return 10;
        if (weeks == 4) return 8;
        if (weeks == 3) return 5;
        return 0;
    }

    /**
     * RSI超跌打分（超跌反弹策略，10分）
     */
    public static int scoreRsiOversold(double rsi) {
        if (rsi < 30) return 10;
        if (rsi < 40) return 5;
        return 0;
    }

    /**
     * 布林带超跌打分（10分）
     */
    public static int scoreBollingerOversold(double price, double lower, double extremeLower) {
        if (price < extremeLower) return 10;
        if (price < lower) return 8;
        return 0;
    }

    /**
     * 历史分位打分（10分）
     */
    public static int scorePercentile(double percentile) {
        if (percentile < 0.10) return 10;
        if (percentile < 0.20) return 5;
        if (percentile < 0.30) return 2;
        return 0;
    }

    // ==================== 投资建议规则 ====================

    /**
     * 根据综合得分给出投资建议
     */
    public static String getInvestmentAdvice(double compositeScore) {
        var props = ScoringPropertiesBridge.get();
        double strongBuy = props != null ? props.getAdvice().getStrongBuy() : 85;
        double buy = props != null ? props.getAdvice().getBuy() : 70;
        double hold = props != null ? props.getAdvice().getHold() : 55;
        double watch = props != null ? props.getAdvice().getWatch() : 30;
        if (compositeScore >= strongBuy) return "强烈买入";
        if (compositeScore >= buy) return "买入";
        if (compositeScore >= hold) return "持有";
        if (compositeScore >= watch) return "观望";
        return "强烈卖出";
    }

    /**
     * 根据综合得分和趋势给出买入信号
     */
    public static String getBuySignal(double score, String trendStatus) {
        java.util.Set<String> strongBuyTrend = java.util.Set.of("强势多头", "多头排列");
        java.util.Set<String> buyTrend = java.util.Set.of("强势多头", "多头排列", "弱势多头");
        java.util.Set<String> strongSellTrend = java.util.Set.of("空头排列", "强势空头");

        if (score >= 75 && strongBuyTrend.contains(trendStatus)) return "强烈买入";
        if (score >= 60 && buyTrend.contains(trendStatus)) return "买入";
        if (score >= 45) return "持有";
        if (score >= 30) return "观望";
        if (strongSellTrend.contains(trendStatus)) return "强烈卖出";
        return "卖出";
    }

    // ==================== 行业调整规则 ====================

    /**
     * 行业适配修正
     */
    public static double getIndustryAdjustment(String industry) {
        if (industry == null) return 0;
        var props = ScoringPropertiesBridge.get();
        if (props != null && props.getIndustryAdjustments() != null) {
            return props.getIndustryAdjustments().getOrDefault(industry, 0.0);
        }
        return switch (industry) {
            case "银行" -> 5.0;
            case "保险" -> 3.0;
            case "房地产" -> -2.0;
            case "新能源" -> 4.0;
            case "科技" -> 3.0;
            default -> 0.0;
        };
    }

    // ==================== P0: 换手率打分 (5分) ====================

    /**
     * 换手率评分：结合换手率与价格方向
     */
    public static double scoreTurnover(double turnoverRate, double priceChange, boolean isRising) {
        if (turnoverRate < ScoringConstants.TURNOVER_COLD) return 1.0;
        if (turnoverRate > ScoringConstants.TURNOVER_HOT) return 0.5;
        if (turnoverRate >= ScoringConstants.TURNOVER_MILD && turnoverRate <= ScoringConstants.TURNOVER_ACTIVE) {
            return isRising ? 5.0 : 2.0;
        }
        if (turnoverRate > ScoringConstants.TURNOVER_ACTIVE) {
            return isRising ? 3.0 : 1.0;
        }
        if (turnoverRate >= ScoringConstants.TURNOVER_COLD && turnoverRate < ScoringConstants.TURNOVER_MILD) {
            return isRising ? 3.0 : 1.5;
        }
        return 2.0;
    }

    // ==================== P0: 指标变化率打分 (10分) ====================

    /**
     * ΔRSI 评分：RSI变化方向与位置组合判断
     */
    public static double scoreDeltaRsi(double rsi, double deltaRsi) {
        double score = 0;
        // RSI从低位回升 → 强看涨信号
        if (rsi < 40 && deltaRsi > 3) score = 5;
        else if (rsi < 40 && deltaRsi > 0) score = 4;
        // RSI在健康区间加速上行
        else if (rsi >= 40 && rsi <= 60 && deltaRsi > 2) score = 4;
        else if (rsi >= 40 && rsi <= 60 && deltaRsi > 0) score = 3;
        // RSI高位回落 → 看跌
        else if (rsi > 70 && deltaRsi < -2) score = 0;
        else if (rsi > 70 && deltaRsi < 0) score = 1;
        // RSI从高位回落但仍在健康范围 → 中性
        else if (rsi > 60 && deltaRsi < 0) score = 2;
        // 其他情况
        else if (deltaRsi > 0) score = 2.5;
        else score = 1.5;
        return score;
    }

    /**
     * ΔMACD柱 评分：MACD动量加速/减速
     */
    public static double scoreDeltaMacd(double macdHist, double deltaMacdHist, String macdStatus) {
        double score = 0;
        boolean isBullish = "多头".equals(macdStatus) || "零轴上金叉".equals(macdStatus)
                || "金叉".equals(macdStatus) || "上穿零轴".equals(macdStatus);
        if (isBullish && deltaMacdHist > 0) score = 5;       // 多头+动量加速
        else if (isBullish && deltaMacdHist <= 0) score = 3;  // 多头+动量减速
        else if (deltaMacdHist > 0 && macdHist < 0) score = 4; // 空头但动量改善
        else if (deltaMacdHist > 0) score = 3;                 // 动量改善
        else if (deltaMacdHist <= 0 && macdHist > 0) score = 1.5;
        else score = 0.5;
        return score;
    }

    // ==================== P1: 日内振幅与影线打分 (5分) ====================

    /**
     * 日内振幅/影线形态评分
     */
    public static double scoreCandlestick(double amplitude, double upperShadow, double lowerShadow,
                                          double bodyRatio, boolean isRising) {
        double score = 2.5;
        // 振幅适中（2-7%）→ 有活性，加分
        if (amplitude >= ScoringConstants.AMPLITUDE_LOW && amplitude <= ScoringConstants.AMPLITUDE_HIGH) {
            score += 1.5;
        } else if (amplitude > ScoringConstants.AMPLITUDE_EXTREME) {
            score -= 1.0;
        }
        // 下影线长 + 上涨 → 买盘承接强
        if (isRising && lowerShadow > 0.4 && lowerShadow > upperShadow) {
            score += 1.5;
        }
        // 上影线长 + 上涨 → 抛压重
        if (isRising && upperShadow > 0.5) {
            score -= 1.0;
        }
        // 实体占比高 → 方向明确
        if (bodyRatio > 0.6) {
            score += 0.5;
        }
        return ScoringUtils.clampSubScore(score, 5.0);
    }

    // ==================== P1: 均线粘合发散打分 ====================

    /**
     * 均线粘合发散评分：粘合后向上发散是强信号
     */
    public static double scoreMaConvergence(double convergenceDegree, double divergenceDirection) {
        // 粘合+多头向上发散 → 最佳买点
        if (convergenceDegree < ScoringConstants.MA_CONVERGENCE_TIGHT && divergenceDirection > 0) {
            return 3.0;
        }
        // 粘合 → 即将选择方向
        if (convergenceDegree < ScoringConstants.MA_CONVERGENCE_TIGHT) {
            return 2.0;
        }
        // 多头排列但不过度发散
        if (divergenceDirection > 0 && convergenceDegree < ScoringConstants.MA_CONVERGENCE_LOOSE) {
            return 1.5;
        }
        // 过度发散 → 可能回调
        if (convergenceDegree > ScoringConstants.MA_CONVERGENCE_LOOSE && divergenceDirection > 0) {
            return 0.5;
        }
        return 1.0;
    }

    // ==================== P2: 波动率收敛打分 ====================

    /**
     * 波动率收敛评分：波动率低位收敛+放量 = 突破前兆
     */
    public static double scoreVolConvergence(double volConvergence, double volPercentile, boolean volumeExpanding) {
        double score = 1.0;
        // 波动率显著收敛（< -20%）→ 变盘前兆
        if (volConvergence < ScoringConstants.VOL_CONVERGENCE_THRESHOLD) {
            score = volumeExpanding ? 3.0 : 2.0;
        }
        // 波动率低位
        else if (volPercentile < 0.25) {
            score = volumeExpanding ? 2.5 : 1.5;
        }
        // 波动率扩张
        else if (volConvergence > ScoringConstants.VOL_EXPANSION_THRESHOLD) {
            score = 0.5;
        }
        return score;
    }

    // ==================== P1: 连续量能状态打分 ====================

    /**
     * 连续放量/缩量状态评分
     */
    public static double scoreConsecutiveVolume(int expandDays, int shrinkDays, boolean isRising) {
        // 连续3天以上放量上涨 → 资金持续介入
        if (expandDays >= ScoringConstants.CONSECUTIVE_VOL_STRONG_SIGNAL && isRising) return 3.0;
        // 连续缩量后企稳 → 底部信号
        if (shrinkDays >= 3 && isRising) return 2.5;
        // 连续放量但不上涨 → 出货嫌疑
        if (expandDays >= ScoringConstants.CONSECUTIVE_VOL_STRONG_SIGNAL && !isRising) return 0.5;
        // 连续缩量下跌 → 弱势
        if (shrinkDays >= 3 && !isRising) return 1.0;
        // 放量1-2天
        if (expandDays >= 1 && isRising) return 2.0;
        return 1.5;
    }
}