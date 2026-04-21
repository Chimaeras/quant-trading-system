package com.quant.service.scoring;

import java.util.Arrays;

/**
 * 技术指标计算工具类
 * 统一封装 MACD、RSI、KDJ、EMA、MA 等常用技术指标计算
 */
public final class TechnicalIndicators {

    private TechnicalIndicators() {}

    // ==================== EMA 计算 ====================

    /**
     * 计算指数移动平均线
     */
    public static double[] ema(double[] values, int span) {
        double alpha = 2.0 / (span + 1);
        double[] out = new double[values.length];
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    // ==================== SMA 计算 ====================

    /**
     * 计算简单移动平均线（数组形式）
     */
    public static double[] sma(double[] values, int period) {
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

    /**
     * 计算指定位置的简单移动平均值
     */
    public static double smaAt(double[] values, int index, int period) {
        if (index < period - 1 || values == null) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = index - period + 1; i <= index; i++) {
            sum += values[i];
        }
        return sum / period;
    }

    // ==================== MACD 计算 ====================

    public record MacdResult(double[] dif, double[] dea, double[] macd) {}

    /**
     * 计算MACD指标
     */
    public static MacdResult macd(double[] close) {
        return macd(close, ScoringConstants.MACD_FAST, ScoringConstants.MACD_SLOW, ScoringConstants.MACD_SIGNAL);
    }

    /**
     * 计算MACD指标（自定义参数）
     */
    public static MacdResult macd(double[] close, int fastPeriod, int slowPeriod, int signalPeriod) {
        double[] emaFast = ema(close, fastPeriod);
        double[] emaSlow = ema(close, slowPeriod);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = emaFast[i] - emaSlow[i];
        }
        double[] dea = ema(dif, signalPeriod);
        double[] macd = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            macd[i] = (dif[i] - dea[i]) * 2;
        }
        return new MacdResult(dif, dea, macd);
    }

    /**
     * 判断MACD状态
     */
    public static String classifyMacd(MacdResult macd, int index) {
        if (index < 1) return "中性";

        double difPrev = macd.dif()[index - 1];
        double difCurr = macd.dif()[index];
        double deaPrev = macd.dea()[index - 1];
        double deaCurr = macd.dea()[index];
        double prevDd = difPrev - deaPrev;
        double currDd = difCurr - deaCurr;

        boolean golden = prevDd <= 0 && currDd > 0;
        boolean death = prevDd >= 0 && currDd < 0;
        boolean crossUpZero = difPrev <= 0 && difCurr > 0;
        boolean crossDownZero = difPrev >= 0 && difCurr < 0;

        if (golden && difCurr > 0 && deaCurr > 0) return "零轴上金叉";
        if (crossUpZero) return "上穿零轴";
        if (golden) return "金叉";
        if (death) return "死叉";
        if (crossDownZero) return "下穿零轴";
        if (difCurr > 0 && deaCurr > 0) return "多头";
        if (difCurr < 0 && deaCurr < 0) return "空头";
        return "中性";
    }

    // ==================== RSI 计算 ====================

    /**
     * 计算指定位置的RSI值
     */
    public static double rsiAt(double[] close, int index, int period) {
        if (index < period || close == null) {
            return 50.0;
        }
        double gainSum = 0;
        double lossSum = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double delta = close[i] - close[i - 1];
            gainSum += Math.max(delta, 0);
            lossSum += Math.max(-delta, 0);
        }
        if (lossSum < 1e-12) return 100.0;
        if (gainSum < 1e-12) return 0.0;
        return 100.0 - 100.0 / (1.0 + gainSum / lossSum);
    }

    /**
     * 计算整条RSI线
     */
    public static double[] rsi(double[] close, int period) {
        double[] out = new double[close.length];
        Arrays.fill(out, 50.0);
        for (int i = period; i < close.length; i++) {
            out[i] = rsiAt(close, i, period);
        }
        return out;
    }

    /**
     * 判断RSI状态
     */
    public static String classifyRsi(double rsi) {
        if (rsi < 30) return "超卖";
        if (rsi <= 70) return "中性";
        return "超买";
    }

    // ==================== KDJ 计算 ====================

    public record KdjResult(double[] k, double[] d, double[] j) {}

    /**
     * 计算KDJ指标
     */
    public static KdjResult kdj(double[] high, double[] low, double[] close, int period) {
        int len = close.length;
        double[] rsv = new double[len];
        Arrays.fill(rsv, 50.0);

        for (int i = period - 1; i < len; i++) {
            double hh = high[i];
            double ll = low[i];
            for (int t = i - period + 1; t < i; t++) {
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
        return new KdjResult(k, d, j);
    }

    /**
     * 判断KDJ状态
     */
    public static String classifyKdj(KdjResult kdj, int index) {
        if (index < 1) return "中性";
        double k0 = kdj.k()[index - 1];
        double d0 = kdj.d()[index - 1];
        double k1 = kdj.k()[index];
        double d1 = kdj.d()[index];

        boolean crossUp = k0 <= d0 && k1 > d1;
        boolean crossDown = k0 >= d0 && k1 < d1;

        if (k1 < 20 && k1 > k0) return "K低位向上";
        if (k1 > 80 && k1 < k0) return "K高位向下";
        if (crossUp) return "K上穿D";
        if (crossDown) return "K下穿D";
        return "中性";
    }

    // ==================== 布林带计算 ====================

    public record BollingerResult(double upper, double middle, double lower) {}

    /**
     * 计算布林带
     */
    public static BollingerResult bollinger(double[] close, int index, int period, double stdDev) {
        double middle = smaAt(close, index, period);
        if (Double.isNaN(middle)) {
            return new BollingerResult(Double.NaN, Double.NaN, Double.NaN);
        }

        double std = stdDev(close, index - period + 1, index);
        return new BollingerResult(
            middle + stdDev * std,
            middle,
            middle - stdDev * std
        );
    }

    // ==================== 统计计算 ====================

    /**
     * 计算标准差
     */
    public static double stdDev(double[] values, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(values.length - 1, to);
        if (f > t) return 0;

        double mean = 0;
        int n = 0;
        for (int i = f; i <= t; i++) {
            mean += values[i];
            n++;
        }
        mean /= n;

        double var = 0;
        for (int i = f; i <= t; i++) {
            double d = values[i] - mean;
            var += d * d;
        }
        return n > 1 ? Math.sqrt(var / n) : 0;
    }

    /**
     * 计算列表区间平均值
     */
    public static double avg(java.util.List<Double> list, int from, int to) {
        if (list == null || list.isEmpty()) return 0;
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        if (f > t) return 0;

        double sum = 0;
        int n = 0;
        for (int i = f; i <= t; i++) {
            sum += list.get(i);
            n++;
        }
        return n > 0 ? sum / n : 0;
    }

    /**
     * 计算列表区间最小值
     */
    public static double min(java.util.List<Double> list, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        double m = Double.POSITIVE_INFINITY;
        for (int i = f; i <= t; i++) {
            m = Math.min(m, list.get(i));
        }
        return m == Double.POSITIVE_INFINITY ? 0 : m;
    }

    /**
     * 计算列表区间最大值
     */
    public static double max(java.util.List<Double> list, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        double m = Double.NEGATIVE_INFINITY;
        for (int i = f; i <= t; i++) {
            m = Math.max(m, list.get(i));
        }
        return m == Double.NEGATIVE_INFINITY ? 0 : m;
    }

    // ==================== 均线排列判断 ====================

    /**
     * 判断均线排列状态
     */
    public static String classifyMaArrangement(double ma5, double ma10, double ma20, double ma60) {
        if (Double.isNaN(ma5) || Double.isNaN(ma10) || Double.isNaN(ma20) || Double.isNaN(ma60)) {
            return "中性排列";
        }

        boolean isBullish = ma5 > ma10 && ma10 > ma20 && ma20 > ma60;
        boolean isBearish = ma5 < ma10 && ma10 < ma20 && ma20 < ma60;

        if (isBullish) return "多头排列";
        if (isBearish) return "空头排列";
        return "中性排列";
    }

    /**
     * 判断均线排列状态（含趋势方向）
     */
    public static String classifyMaArrangement(double[] close, int index) {
        double ma5 = smaAt(close, index, 5);
        double ma10 = smaAt(close, index, 10);
        double ma20 = smaAt(close, index, 20);
        double ma60 = smaAt(close, index, 60);

        if (Double.isNaN(ma5) || Double.isNaN(ma10) || Double.isNaN(ma20) || Double.isNaN(ma60)) {
            return "中性排列";
        }

        boolean isBullish = ma5 > ma10 && ma10 > ma20 && ma20 > ma60;
        boolean isBearish = ma5 < ma10 && ma10 < ma20 && ma20 < ma60;

        // 检查趋势方向
        double ma5Prev = smaAt(close, index - 1, 5);
        double ma10Prev = smaAt(close, index - 1, 10);
        boolean isTrendingUp = !Double.isNaN(ma5Prev) && !Double.isNaN(ma10Prev) && ma5 > ma5Prev && ma10 > ma10Prev;

        if (isBullish && isTrendingUp) return "多头排列";
        if (isBullish) return "弱势多头";
        if (isBearish) return "空头排列";
        return "中性排列";
    }

    // ==================== 量价关系判断 ====================

    /**
     * 判断量价状态
     */
    public static String classifyVolumePrice(double[] close, double[] volume, int index) {
        if (index < 4) return "量能正常";

        double volToday = volume[index];
        double volAvg3 = 0;
        for (int i = index - 3; i <= index - 1; i++) {
            volAvg3 += volume[i];
        }
        volAvg3 /= 3;

        double ratio = volAvg3 > 1e-9 ? volToday / volAvg3 : 1.0;
        double priceChange = close[index - 1] > 1e-9
            ? (close[index] - close[index - 1]) / close[index - 1] * 100
            : 0;

        boolean volUp50 = ratio >= 1.5;
        boolean priceUp = priceChange > 0;

        if (volUp50 && priceUp) return "量价齐升";
        if ((priceUp && ratio < 0.75) || (!priceUp && ratio > 1.4)) return "量价背离";
        return "量能正常";
    }
}