package com.quant.service.scoring;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 信号时间衰减：effectiveScore = rawScore * exp(-ln(2) * days / halfLife)。
 */
public final class SignalDecay {

    private SignalDecay() {}

    public static double apply(double rawScore, LocalDate signalDate, LocalDate asOfDate, int halfLifeDays) {
        if (rawScore == 0 || signalDate == null || asOfDate == null || halfLifeDays <= 0) {
            return rawScore;
        }
        long days = Math.max(0, ChronoUnit.DAYS.between(signalDate, asOfDate));
        double factor = Math.exp(-Math.log(2) * days / halfLifeDays);
        return rawScore * factor;
    }

    public static int technicalHalfLife() {
        var p = ScoringPropertiesBridge.get();
        return p != null ? p.getDecay().getTechnicalHalfLifeDays() : 5;
    }

    public static int newsHalfLife() {
        var p = ScoringPropertiesBridge.get();
        return p != null ? p.getDecay().getNewsHalfLifeDays() : 2;
    }

    public static int fundamentalHalfLife() {
        var p = ScoringPropertiesBridge.get();
        return p != null ? p.getDecay().getFundamentalHalfLifeDays() : 90;
    }
}
