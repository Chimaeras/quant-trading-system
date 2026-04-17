package com.quant.service.scoring;

import java.math.BigDecimal;

/** 《打分系统说明》市场轮动三项相加。 */
public final class RotationDocScoring {

    private RotationDocScoring() {}

    /** 近 5 日主力净流入（亿元） */
    public static double scoreMainNet5d(double yi) {
        if (yi > 10) {
            return 8;
        }
        if (yi < -5) {
            return -6;
        }
        return 0;
    }

    /** 板块涨幅排名：前 3 +6；后 3 -3；rank 从 1 最好 */
    public static double scoreSectorRank(int rank, int totalSectors) {
        if (totalSectors < 6) {
            return 0;
        }
        if (rank >= 1 && rank <= 3) {
            return 6;
        }
        if (rank >= totalSectors - 2) {
            return -3;
        }
        return 0;
    }

    public static double scoreMarginDelta(BigDecimal prevBalance, BigDecimal currBalance) {
        if (prevBalance == null || currBalance == null) {
            return 0;
        }
        return currBalance.compareTo(prevBalance) > 0 ? 4 : -4;
    }
}
