package com.quant.service.scoring;

import java.math.BigDecimal;

/** 《打分系统说明》市场轮动三项相加。阈值支持通过 application.yml 配置。 */
public final class RotationDocScoring {

    private RotationDocScoring() {}

    /** 近 5 日主力净流入（亿元），阈值可通过 quant.scoring.rotation 配置 */
    public static double scoreMainNet5d(double yi) {
        var props = ScoringPropertiesBridge.get();
        double inflowThreshold = props != null && props.getRotation() != null
                ? props.getRotation().getInflowThreshold() : 10.0;
        double outflowThreshold = props != null && props.getRotation() != null
                ? props.getRotation().getOutflowThreshold() : -5.0;
        if (yi > inflowThreshold) return 8;
        if (yi < outflowThreshold) return -6;
        return 0;
    }

    /** 板块涨幅排名，topN/bottomN 可通过 quant.scoring.rotation 配置 */
    public static double scoreSectorRank(int rank, int totalSectors) {
        if (totalSectors < 6) return 0;
        var props = ScoringPropertiesBridge.get();
        int topN = props != null && props.getRotation() != null
                ? props.getRotation().getTopRankBonus() : 3;
        int bottomN = props != null && props.getRotation() != null
                ? props.getRotation().getBottomRankPenalty() : 3;
        if (rank >= 1 && rank <= topN) return 6;
        if (rank >= totalSectors - bottomN + 1) return -3;
        return 0;
    }

    public static double scoreMarginDelta(BigDecimal prevBalance, BigDecimal currBalance) {
        if (prevBalance == null || currBalance == null) {
            return 0;
        }
        return currBalance.compareTo(prevBalance) > 0 ? 4 : -4;
    }
}
