package com.quant.service.scoring;

import com.quant.entity.MarketData;

import java.util.Arrays;
import java.util.List;

/**
 * 《打分系统说明》技术面：MACD/RSI/KDJ/成交量/均线加权子分。
 * 重构版本：使用统一的 TechnicalIndicators 和 ScoringRules
 */
public final class TechnicalDocScoring {

    private TechnicalDocScoring() {}

    public record TechDocResult(
            double macdScore,
            double rsiScore,
            double kdjScore,
            double volumeScore,
            double maScore,
            double technicalWeighted,
            String macdStatus,
            String rsiStatus,
            String kdjStatus,
            String volumeStatus,
            String maStatus
    ) {}

    public static TechDocResult score(List<MarketData> bars) {
        if (bars == null || bars.size() < ScoringConstants.MA60_PERIOD + 1) {
            return new TechDocResult(0, 0, 0, 0, 0, 0, "数据不足", "数据不足", "数据不足", "数据不足", "数据不足");
        }

        int n = bars.size();
        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] vol = new double[n];

        for (int i = 0; i < n; i++) {
            MarketData m = bars.get(i);
            close[i] = m.getClosePrice() != null ? m.getClosePrice().doubleValue() : Double.NaN;
            high[i] = m.getHighPrice() != null ? m.getHighPrice().doubleValue() : close[i];
            low[i] = m.getLowPrice() != null ? m.getLowPrice().doubleValue() : close[i];
            Long v = m.getVolume();
            vol[i] = v != null ? v.doubleValue() : 0;
        }

        if (Arrays.stream(close).anyMatch(Double::isNaN)) {
            return new TechDocResult(0, 0, 0, 0, 0, 0, "数据不足", "数据不足", "数据不足", "数据不足", "数据不足");
        }

        int L = n - 1;

        // 使用统一的技术指标计算工具
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(close);
        String macdStatus = TechnicalIndicators.classifyMacd(macd, L);
        double macdScore = ScoringRules.scoreMacd(macdStatus);

        double rsi = TechnicalIndicators.rsiAt(close, L, ScoringConstants.RSI_PERIOD);
        String rsiStatus = TechnicalIndicators.classifyRsi(rsi);
        double rsiScore = ScoringRules.scoreRsi(rsi);

        TechnicalIndicators.KdjResult kdj = TechnicalIndicators.kdj(high, low, close, ScoringConstants.KDJ_PERIOD);
        String kdjStatus = TechnicalIndicators.classifyKdj(kdj, L);
        double kdjScore = ScoringRules.scoreKdj(kdjStatus);

        String volStatus = TechnicalIndicators.classifyVolumePrice(close, vol, L);
        double volScore = ScoringRules.scoreVolumePrice(volStatus);

        String maStatus = TechnicalIndicators.classifyMaArrangement(close, L);
        double maScore = ScoringRules.scoreMaArrangement(maStatus);

        // 加权计算技术面总分
        double tech = macdScore * 0.3 + rsiScore * 0.2 + kdjScore * 0.1 + volScore * 0.1 + maScore * 0.3;

        return new TechDocResult(macdScore, rsiScore, kdjScore, volScore, maScore,
                ScoringUtils.round2(tech), macdStatus, rsiStatus, kdjStatus, volStatus, maStatus);
    }

}
