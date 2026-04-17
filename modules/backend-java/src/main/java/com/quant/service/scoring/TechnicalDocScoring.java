package com.quant.service.scoring;

import com.quant.entity.MarketData;

import java.util.List;

/**
 * 《打分系统说明》技术面：MACD/RSI/KDJ/成交量加权子分。
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

    private static final int MACD_MIN = 26;
    private static final int RSI_PERIOD = 14;
    private static final int KDJ_N = 9;
    private static final int MA5_PERIOD = 5;
    private static final int MA10_PERIOD = 10;
    private static final int MA20_PERIOD = 20;
    private static final int MA60_PERIOD = 60;

    public static TechDocResult score(List<MarketData> bars) {
        if (bars == null || bars.size() < MA60_PERIOD + 1) {
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
        if (java.util.Arrays.stream(close).anyMatch(Double::isNaN)) {
            return new TechDocResult(0, 0, 0, 0, 0, 0, "数据不足", "数据不足", "数据不足", "数据不足", "数据不足");
        }

        int L = n - 1;
        MacdPack macd = macdLines(close);
        String macdClass = classifyMacdDoc(macd, L);
        double macdS = scoreMacdDoc(macdClass);

        double rsi = rsiRolling(close, L, RSI_PERIOD);
        String rsiClass = classifyRsiDoc(rsi);
        double rsiS = scoreRsiDoc(String.valueOf(rsi));

        KdjPack kdj = kdjLines(high, low, close, KDJ_N);
        String kdjClass = classifyKdjDoc(kdj, L);
        double kdjS = scoreKdjDoc(kdjClass);

        String volClass = classifyVolumeDoc(close, vol, L);
        double volS = scoreVolumeDoc(volClass);

        double[] ma5 = ma(close, MA5_PERIOD);
        double[] ma10 = ma(close, MA10_PERIOD);
        double[] ma20 = ma(close, MA20_PERIOD);
        double[] ma60 = ma(close, MA60_PERIOD);
        String maClass = classifyMaDoc(ma5, ma10, ma20, ma60, L);
        double maS = scoreMaDoc(maClass);

        double tech = macdS * 0.3 + rsiS * 0.2 + kdjS * 0.1 + volS * 0.1 + maS * 0.3;
        return new TechDocResult(macdS, rsiS, kdjS, volS, maS, tech, macdClass, rsiClass, kdjClass, volClass, maClass);
    }

    private record MacdPack(double[] dif, double[] dea) {}

    private static MacdPack macdLines(double[] close) {
        double[] ema12 = ema(close, 12);
        double[] ema26 = ema(close, 26);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = ema12[i] - ema26[i];
        }
        double[] dea = ema(dif, 9);
        return new MacdPack(dif, dea);
    }

    private static double[] ema(double[] values, int span) {
        double alpha = 2.0 / (span + 1);
        double[] out = new double[values.length];
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    /** 文档 MACD：水上金叉/普通金叉/水下死叉/普通死叉/无信号 */
    private static String classifyMacdDoc(MacdPack m, int L) {
        if (L < 1) return "无信号";
        double difPrev = m.dif()[L - 1];
        double difCurr = m.dif()[L];
        double deaPrev = m.dea()[L - 1];
        double deaCurr = m.dea()[L];
        double prevDd = difPrev - deaPrev;
        double currDd = difCurr - deaCurr;
        boolean golden = prevDd <= 0 && currDd > 0;
        boolean death = prevDd >= 0 && currDd < 0;
        if (golden) {
            if (difCurr > 0 && deaCurr > 0) {
                return "水上金叉";
            }
            return "普通金叉";
        }
        if (death) {
            if (difCurr < 0 && deaCurr < 0) {
                return "水下死叉";
            }
            return "普通死叉";
        }
        return "无信号";
    }

    private static double scoreMacdDoc(String c) {
        return switch (c) {
            case "水上金叉" -> 15;
            case "普通金叉" -> 10;
            case "水下死叉" -> -15;
            case "普通死叉" -> -10;
            default -> 0;
        };
    }

    private static double rsiRolling(double[] close, int i, int period) {
        if (i < period) {
            return 50.0;
        }
        double gainSum = 0;
        double lossSum = 0;
        for (int k = i - period + 1; k <= i; k++) {
            double delta = close[k] - close[k - 1];
            gainSum += Math.max(delta, 0);
            lossSum += Math.max(-delta, 0);
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss < 1e-12) {
            return 100.0;
        }
        if (avgGain < 1e-12) {
            return 0.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    private static String classifyRsiDoc(double rsi) {
        if (rsi < 30) return "超卖";
        if (rsi <= 70) return "中性";
        return "超买";
    }

    private static double scoreRsiDoc(String c) {
        return switch (c) {
            case "超卖" -> 10;
            case "中性" -> 5;
            case "超买" -> -10;
            default -> 5;
        };
    }

    private record KdjPack(double[] k, double[] d, double[] j) {}

    private static KdjPack kdjLines(double[] high, double[] low, double[] close, int n) {
        int len = close.length;
        double[] rsv = new double[len];
        java.util.Arrays.fill(rsv, 50.0);
        for (int i = n - 1; i < len; i++) {
            double hh = high[i];
            double ll = low[i];
            for (int t = i - n + 1; t < i; t++) {
                hh = Math.max(hh, high[t]);
                ll = Math.min(ll, low[t]);
            }
            double denom = hh - ll;
            rsv[i] = denom < 1e-9 ? 50.0 : (close[i] - ll) / denom * 100.0;
        }
        double[] k = new double[len];
        double[] d = new double[len];
        double[] j = new double[len];
        k[0] = rsv[0];
        d[0] = rsv[0];
        for (int i = 1; i < len; i++) {
            k[i] = (2.0 * k[i - 1] + rsv[i]) / 3.0;
            d[i] = (2.0 * d[i - 1] + k[i]) / 3.0;
            j[i] = 3.0 * k[i] - 2.0 * d[i];
        }
        return new KdjPack(k, d, j);
    }

    private static String classifyKdjDoc(KdjPack p, int L) {
        if (L < 1) return "中性";
        double k0 = p.k()[L - 1];
        double d0 = p.d()[L - 1];
        double k1 = p.k()[L];
        double d1 = p.d()[L];
        boolean crossUp = k0 <= d0 && k1 > d1;
        boolean crossDown = k0 >= d0 && k1 < d1;
        if (k1 < 20 && k1 > k0) {
            return "K低位向上";
        }
        if (k1 > 80 && k1 < k0) {
            return "K高位向下";
        }
        if (crossUp) {
            return "K上穿D";
        }
        if (crossDown) {
            return "K下穿D";
        }
        return "中性";
    }

    private static double scoreKdjDoc(String c) {
        return switch (c) {
            case "K低位向上" -> 12;
            case "K高位向下" -> -12;
            case "K上穿D" -> 8;
            case "K下穿D" -> -8;
            default -> 0;
        };
    }

    /** 近 3 日均量对比今日；量价齐升 / 背离 / 正常 */
    private static String classifyVolumeDoc(double[] close, double[] vol, int L) {
        if (L < 4) return "量能正常";
        double volToday = vol[L];
        double sum = 0;
        int cnt = 0;
        for (int i = L - 3; i <= L - 1; i++) {
            sum += vol[i];
            cnt++;
        }
        double avg3 = cnt > 0 ? sum / cnt : 0;
        double ratio = avg3 > 1e-9 ? volToday / avg3 : 1.0;
        double prevClose = close[L - 1];
        double priceChangePct = prevClose != 0 ? (close[L] - prevClose) / prevClose * 100.0 : 0;
        boolean volUp50 = ratio >= 1.5;
        boolean priceUp = priceChangePct > 0;
        if (volUp50 && priceUp) {
            return "量价齐升";
        }
        if ((priceUp && ratio < 0.75) || (!priceUp && ratio > 1.4)) {
            return "量价背离";
        }
        return "量能正常";
    }

    private static double scoreVolumeDoc(String c) {
        return switch (c) {
            case "量价齐升" -> 10;
            case "量价背离" -> -7;
            case "量能正常" -> 3;
            default -> 3;
        };
    }

    private static double[] ma(double[] values, int period) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            if (i < period - 1) {
                out[i] = values[i];
            } else {
                double sum = 0;
                for (int j = i - period + 1; j <= i; j++) {
                    sum += values[j];
                }
                out[i] = sum / period;
            }
        }
        return out;
    }

    private static String classifyMaDoc(double[] ma5, double[] ma10, double[] ma20, double[] ma60, int L) {
        if (L < 1) return "中性排列";
        double ma5Val = ma5[L];
        double ma10Val = ma10[L];
        double ma20Val = ma20[L];
        double ma60Val = ma60[L];
        double ma5Prev = ma5[L-1];
        double ma10Prev = ma10[L-1];
        double ma20Prev = ma20[L-1];
        double ma60Prev = ma60[L-1];

        boolean isBullish = ma5Val > ma10Val && ma10Val > ma20Val && ma20Val > ma60Val;
        boolean isBearish = ma5Val < ma10Val && ma10Val < ma20Val && ma20Val < ma60Val;
        boolean isBullishTrend = ma5Val > ma5Prev && ma10Val > ma10Prev && ma20Val > ma20Prev && ma60Val > ma60Prev;
        boolean isBearishTrend = ma5Val < ma5Prev && ma10Val < ma10Prev && ma20Val < ma20Prev && ma60Val < ma60Prev;

        if (isBullish && isBullishTrend) {
            return "多头排列";
        } else if (isBearish && isBearishTrend) {
            return "空头排列";
        } else {
            return "中性排列";
        }
    }

    private static double scoreMaDoc(String c) {
        return switch (c) {
            case "多头排列" -> 10;
            case "中性排列" -> 5;
            case "空头排列" -> 0;
            default -> 5;
        };
    }
}
