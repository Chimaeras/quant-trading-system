package com.quant.service.scoring;

import com.quant.service.scoring.model.CandidateScore;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 经典因子 VIF（方差膨胀因子）检测，共线性 > 5 时输出告警日志。
 */
@Slf4j
public final class FactorCollinearityChecker {

    private static final double VIF_WARN_THRESHOLD = 5.0;

    private FactorCollinearityChecker() {}

    public static void logVifReport(List<CandidateScore> candidates) {
        if (candidates == null || candidates.size() < 10) {
            return;
        }
        String[] names = {"trend", "momentum", "volume", "risk"};
        double[][] x = new double[candidates.size()][4];
        for (int i = 0; i < candidates.size(); i++) {
            CandidateScore c = candidates.get(i);
            x[i][0] = c.getTrendScore();
            x[i][1] = c.getMomentumScore();
            x[i][2] = c.getVolumeScore();
            x[i][3] = c.getRiskPenalty();
        }
        for (int j = 0; j < 4; j++) {
            double vif = computeVif(x, j);
            if (vif > VIF_WARN_THRESHOLD) {
                log.warn("[factor-vif] 因子共线性告警 factor={} vif={} sampleSize={} (阈值>{})",
                        names[j], ScoringUtils.round2(vif), candidates.size(), VIF_WARN_THRESHOLD);
            }
        }
    }

    private static double computeVif(double[][] x, int targetCol) {
        int n = x.length;
        int p = x[0].length - 1;
        if (n <= p + 2) return 1.0;

        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = x[i][targetCol];

        double ssTot = 0;
        double meanY = 0;
        for (double v : y) meanY += v;
        meanY /= n;
        for (double v : y) ssTot += (v - meanY) * (v - meanY);

        if (ssTot < 1e-12) return 1.0;

        double ssRes = ssTot;
        int cols = 0;
        for (int j = 0; j < x[0].length; j++) {
            if (j == targetCol) continue;
            cols++;
            double meanX = 0;
            for (int i = 0; i < n; i++) meanX += x[i][j];
            meanX /= n;
            double cov = 0, varX = 0;
            for (int i = 0; i < n; i++) {
                cov += (x[i][j] - meanX) * (y[i] - meanY);
                varX += (x[i][j] - meanX) * (x[i][j] - meanX);
            }
            if (varX < 1e-12) continue;
            double beta = cov / varX;
            double intercept = meanY - beta * meanX;
            for (int i = 0; i < n; i++) {
                double pred = intercept + beta * x[i][j];
                ssRes -= (y[i] - pred) * (y[i] - pred) / cols;
            }
        }
        ssRes = Math.max(1e-12, ssTot - Math.max(0, ssTot - ssRes));
        double r2 = 1 - ssRes / ssTot;
        r2 = ScoringUtils.clamp(r2, 0, 0.999);
        return 1.0 / (1.0 - r2);
    }
}
