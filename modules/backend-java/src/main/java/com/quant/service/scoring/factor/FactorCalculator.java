package com.quant.service.scoring.factor;

import com.quant.entity.MarketData;
import com.quant.service.scoring.CrossSectionNeutralizer;
import com.quant.service.scoring.FinancialSnapshotLoader;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.TechnicalIndicators;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L1 层计算器：{@code List<MarketData>} → {@link FeatureVector}。
 *
 * <p>本类<b>不做可交易性判断，也不做最小 bar 数拒绝</b>——那些由调用方在外层处理。
 * 这里只负责：能算的算出来，不能算的留 {@code null} 并记下原因。
 *
 * <p>与既有实现的口径对齐（避免"重构即改数"）：
 * <ul>
 *   <li>收益率用 {@link ScoringUtils#pct(double, double)}（与 {@code OversoldReboundScoringService} 同源）。</li>
 *   <li>20 日波动率用 {@link ScoringUtils#stdPct(List, int)} × 100
 *       （与 {@code ClassicFactorCalculator} 同源；<b>不是</b> {@code TechnicalIndicators.stdDev}
 *       的价格水平标准差，两者口径不同不可互换）。</li>
 *   <li>60 日最大回撤用 {@link ScoringUtils#maxDrawdown(List, int)}。</li>
 *   <li>20 日日均成交额用 {@link ScoringUtils#avgAmount20(List)}（跳过显式零成交、NULL amount 记 0）。</li>
 *   <li>RSI 用 {@code TechnicalIndicators.rsi} 的 Wilder 递推；KDJ 初值 50.0；EMA 为 SMA 播种。</li>
 * </ul>
 */
public final class FactorCalculator {

    /** MACD 参数与既有一致（12/26/9）。 */
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private static final int RSI_PERIOD = 14;
    private static final int KDJ_PERIOD = 9;

    /** 量能放量阈值，与 {@code ScoringConstants.VOLUME_HEAVY_RATIO} 一致。 */
    private static final double VOLUME_HEAVY_RATIO = 1.5;
    /** 量能缩量阈值，与 {@code ScoringConstants.VOLUME_SHRINK_RATIO} 一致。 */
    private static final double VOLUME_SHRINK_RATIO = 0.7;

    private FactorCalculator() {}

    /** 计算结果：特征向量 + 可用因子集合 + 不可用原因。 */
    public record Outcome(
            FeatureVector features,
            Set<FactorId> available,
            Map<FactorId, String> unavailableReasons
    ) {
        public boolean isAvailable(FactorId id) {
            return available.contains(id);
        }
    }

    /**
     * 从已按交易日排序（升序）的 K 线序列计算特征。
     *
     * @param bars     已排序且已按交易日去重；允许为空/null（将返回全 null 特征）
     * @param industry PIT 行业（本库当前恒为 null/空）；为 null 时标注为缺失
     */
    public static Outcome compute(
            String stockCode,
            String exchangeType,
            String stockName,
            java.time.LocalDate tradeDate,
            List<MarketData> bars,
            String industry) {
        return compute(stockCode, exchangeType, stockName, tradeDate, bars, industry, null);
    }

    /**
     * 带财务快照的重载：使 {@code QUAL_ROE / QUAL_LEV / LIQ_CURRENT} 三个基本面因子真正可算。
     *
     * <p><b>这是修一个真实缺陷</b>：此前 {@code compute} 不接受财务快照，
     * 于是这三个因子被**无条件**标为不可用（原因写死为 {@code NO_FINANCIAL_SNAPSHOT_SOURCE}），
     * 即使调用方已经把 {@code stock_financial_snapshot} 装进内存也用不上。
     * 后果是"综合"/"多维度"两个策略里 40% 的基本面权重**结构性空转**。
     *
     * <p>注意 PE/PB **不在此处**：它们来自 {@code market_data.pe_ratio / pb_ratio} 两根行情列
     * （本库 100% NULL），与财务快照是两个独立数据源。混在一起会让
     * {@code NULL_PE_RATIO} 的披露失真，故仍由行情列决定。
     *
     * @param snapshot 该股在信号日可得的最新财报快照；null 表示确实无数据，
     *                 此时照实标注 {@code NO_FINANCIAL_SNAPSHOT_SOURCE}
     */
    public static Outcome compute(
            String stockCode,
            String exchangeType,
            String stockName,
            java.time.LocalDate tradeDate,
            List<MarketData> bars,
            String industry,
            com.quant.entity.StockFinancialSnapshot snapshot) {

        List<MarketData> safeBars = bars != null ? bars : List.of();
        int n = safeBars.size();
        FeatureVector fv = FeatureVector.of(stockCode, exchangeType, stockName, tradeDate, n);
        Set<FactorId> available = new LinkedHashSet<>();
        Map<FactorId, String> reasons = new LinkedHashMap<>();

        fv.setIndustry(industry == null || industry.isBlank() ? null : industry);

        if (n == 0) {
            for (FactorId id : FactorId.values()) {
                reasons.put(id, "NO_BARS");
            }
            return new Outcome(fv, available, reasons);
        }

        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] volume = new double[n];
        List<Double> closeList = new ArrayList<>(n);
        boolean hasInvalidClose = false;
        for (int i = 0; i < n; i++) {
            MarketData b = safeBars.get(i);
            close[i] = b.getClosePrice() != null ? b.getClosePrice().doubleValue() : Double.NaN;
            high[i] = b.getHighPrice() != null ? b.getHighPrice().doubleValue() : Double.NaN;
            low[i] = b.getLowPrice() != null ? b.getLowPrice().doubleValue() : Double.NaN;
            volume[i] = b.getVolume() != null ? b.getVolume().doubleValue() : 0d;
            if (Double.isNaN(close[i]) || close[i] <= 0) {
                hasInvalidClose = true;
            }
            closeList.add(close[i]);
        }

        MarketData last = safeBars.get(n - 1);
        double lastClose = close[n - 1];

        // ── 收益率 ────────────────────────────────────────────────────
        setRet(fv, FactorId.MOM_5, available, reasons, n, 6, lastClose, close, 5, fv::setRet5);
        setRet(fv, FactorId.MOM_20, available, reasons, n, 21, lastClose, close, 20, fv::setRet20);
        setRet(fv, FactorId.MOM_60, available, reasons, n, 61, lastClose, close, 60, fv::setRet60);
        setRet(fv, FactorId.MOM_120, available, reasons, n, 121, lastClose, close, 120, fv::setRet120);
        if (n >= 2 && !Double.isNaN(close[n - 2]) && close[n - 2] > 0
                && !Double.isNaN(lastClose) && lastClose > 0) {
            fv.setRet1(ScoringUtils.pct(lastClose, close[n - 2]));
        }
        // REV_5 与 MOM_5 同源取反，可用性一致
        if (available.contains(FactorId.MOM_5)) {
            available.add(FactorId.REV_5);
        } else {
            reasons.put(FactorId.REV_5, reasons.getOrDefault(FactorId.MOM_5, "INSUFFICIENT_BARS"));
        }

        // ── 均线 / 趋势 ───────────────────────────────────────────────
        double[] ma5 = TechnicalIndicators.sma(close, 5);
        double[] ma10 = TechnicalIndicators.sma(close, 10);
        double[] ma20 = TechnicalIndicators.sma(close, 20);
        double[] ma60 = TechnicalIndicators.sma(close, 60);
        double[] ma120 = TechnicalIndicators.sma(close, 120);
        int lastIdx = n - 1;
        fv.setMa5(ma5[lastIdx]);
        fv.setMa10(ma10[lastIdx]);
        fv.setMa20(ma20[lastIdx]);
        fv.setMa60(ma60[lastIdx]);
        fv.setMa120(ma120[lastIdx]);

        if (n >= 60 && !hasInvalidClose) {
            double align = maAlignmentStrength(ma5[lastIdx], ma10[lastIdx], ma20[lastIdx], ma60[lastIdx]);
            if (Double.isNaN(align)) {
                reasons.put(FactorId.TREND_MA, "INDICATOR_UNDEFINED");
            } else {
                fv.setTrendMaAlignment(align);
                available.add(FactorId.TREND_MA);
            }
        } else {
            reasons.put(FactorId.TREND_MA, hasInvalidClose ? "INVALID_CLOSE_IN_WINDOW" : "INSUFFICIENT_BARS");
        }

        if (n >= 6 && !hasInvalidClose && ma5[lastIdx] > 0) {
            fv.setBias5((lastClose - ma5[lastIdx]) / ma5[lastIdx] * 100.0);
            available.add(FactorId.TREND_BIAS);
        } else {
            reasons.put(FactorId.TREND_BIAS, hasInvalidClose ? "INVALID_CLOSE_IN_WINDOW" : "INSUFFICIENT_BARS");
        }

        // MACD 需要 slow+signal 根才稳定
        if (n >= MACD_SLOW + MACD_SIGNAL && !hasInvalidClose) {
            TechnicalIndicators.MacdResult macd =
                    TechnicalIndicators.macd(close, MACD_FAST, MACD_SLOW, MACD_SIGNAL);
            double hist = macd.macd()[lastIdx];
            if (!Double.isNaN(hist) && lastClose > 0) {
                fv.setMacdHistPct(hist / lastClose * 100.0);
                available.add(FactorId.TREND_MACD);
            } else {
                reasons.put(FactorId.TREND_MACD, "INDICATOR_UNDEFINED");
            }
        } else {
            reasons.put(FactorId.TREND_MACD, hasInvalidClose ? "INVALID_CLOSE_IN_WINDOW" : "INSUFFICIENT_BARS");
        }

        // ── RSI / KDJ ─────────────────────────────────────────────────
        if (n >= RSI_PERIOD + 1 && !hasInvalidClose) {
            double rsi = TechnicalIndicators.rsiAt(close, lastIdx, RSI_PERIOD);
            if (!Double.isNaN(rsi)) {
                fv.setRsi14(rsi);
                available.add(FactorId.RSI_14);
            } else {
                reasons.put(FactorId.RSI_14, "INDICATOR_UNDEFINED");
            }
        } else {
            reasons.put(FactorId.RSI_14, hasInvalidClose ? "INVALID_CLOSE_IN_WINDOW" : "INSUFFICIENT_BARS");
        }

        if (n >= KDJ_PERIOD + 1 && !hasInvalidClose && allFinite(high, low)) {
            TechnicalIndicators.KdjResult kdj =
                    TechnicalIndicators.kdj(high, low, close, KDJ_PERIOD);
            double j = kdj.j()[lastIdx];
            if (!Double.isNaN(j)) {
                fv.setKdjJ(j);
                available.add(FactorId.KDJ_J);
            } else {
                reasons.put(FactorId.KDJ_J, "INDICATOR_UNDEFINED");
            }
        } else {
            reasons.put(FactorId.KDJ_J, hasInvalidClose ? "INVALID_CLOSE_IN_WINDOW" : "INSUFFICIENT_BARS");
        }

        // ── 量能 / 流动性 ──────────────────────────────────────────────
        if (n >= 31) {
            double v5 = TechnicalIndicators.avg(volume, n - 5, n - 1);
            double v30 = TechnicalIndicators.avg(volume, n - 30, n - 1);
            if (v30 > 0) {
                fv.setVolumeRatio(v5 / v30);
                available.add(FactorId.VOL_RATIO);
            } else {
                reasons.put(FactorId.VOL_RATIO, "ZERO_BASE_VOLUME");
            }
        } else {
            reasons.put(FactorId.VOL_RATIO, "INSUFFICIENT_BARS");
        }

        if (last.getTurnoverRate() != null) {
            fv.setTurnoverRate(last.getTurnoverRate().doubleValue());
            available.add(FactorId.VOL_TURNOVER);
        } else {
            reasons.put(FactorId.VOL_TURNOVER, "NULL_TURNOVER_RATE");
        }

        // 与 ScoringUtils.avgAmount20 口径一致：不足 20 根时按实际日数平均，仍可用
        fv.setAvgAmount20(ScoringUtils.avgAmount20(safeBars));
        available.add(FactorId.LIQUIDITY_20);

        if (n >= 31 && fv.volumeRatio() != null) {
            Double vpd = volPriceDirection(fv.volumeRatio(), fv.ret5());
            if (vpd != null) {
                fv.setVolPriceConfirm(vpd);
                available.add(FactorId.VOL_PRICE);
            } else {
                reasons.put(FactorId.VOL_PRICE, "MISSING_INPUT");
            }
        } else {
            reasons.put(FactorId.VOL_PRICE, "INSUFFICIENT_BARS");
        }

        // ── 风险 ──────────────────────────────────────────────────────
        if (n >= 21 && !hasInvalidClose) {
            fv.setVolatility20(ScoringUtils.stdPct(closeList, 20) * 100.0);
            available.add(FactorId.VOLATILITY_20);
        } else {
            reasons.put(FactorId.VOLATILITY_20, hasInvalidClose ? "INVALID_CLOSE_IN_WINDOW" : "INSUFFICIENT_BARS");
        }

        if (n >= 60 && !hasInvalidClose) {
            fv.setMaxDrawdown60(ScoringUtils.maxDrawdown(closeList, 60));
            available.add(FactorId.MAXDD_60);
        } else {
            reasons.put(FactorId.MAXDD_60, hasInvalidClose ? "INVALID_CLOSE_IN_WINDOW" : "INSUFFICIENT_BARS");
        }

        // ── 估值 / 财务 ────────────────────────────────────────────────
        // 注意：本库实测 pe_ratio / pb_ratio 填充率 0.0%（256 万行全 NULL），
        // stock_financial_snapshot 0 行。故这些因子当前必然不可用。
        // 此处如实标注，绝不填中性值——「拿常数打分却宣称多维度」正是要修的问题。
        if (last.getPeRatio() != null && last.getPeRatio().doubleValue() > 0) {
            fv.setPeRatio(last.getPeRatio().doubleValue());
            available.add(FactorId.VAL_EP);
        } else {
            reasons.put(FactorId.VAL_EP, last.getPeRatio() == null ? "NULL_PE_RATIO" : "NON_POSITIVE_PE");
        }
        if (last.getPbRatio() != null && last.getPbRatio().doubleValue() > 0) {
            fv.setPbRatio(last.getPbRatio().doubleValue());
            available.add(FactorId.VAL_BP);
        } else {
            reasons.put(FactorId.VAL_BP, last.getPbRatio() == null ? "NULL_PB_RATIO" : "NON_POSITIVE_PB");
        }
        reasons.put(FactorId.QUAL_ROE, null);
        if (snapshot != null) {
            Double roe = toDouble(snapshot.getRoe());
            if (roe != null) {
                fv.setRoe(roe);
                available.add(FactorId.QUAL_ROE);
                reasons.remove(FactorId.QUAL_ROE);
            } else {
                reasons.put(FactorId.QUAL_ROE, "NULL_ROE_IN_SNAPSHOT");
            }
            Double debt = toDouble(snapshot.getDebtRatio());
            if (debt != null) {
                fv.setDebtRatio(debt);
                available.add(FactorId.QUAL_LEV);
            } else {
                reasons.put(FactorId.QUAL_LEV, "NULL_DEBT_RATIO_IN_SNAPSHOT");
            }
            Double cur = toDouble(snapshot.getCurrentRatio());
            if (cur != null) {
                fv.setCurrentRatio(cur);
                available.add(FactorId.LIQ_CURRENT);
            } else {
                reasons.put(FactorId.LIQ_CURRENT, "NULL_CURRENT_RATIO_IN_SNAPSHOT");
            }
        } else {
            // 如实标注：确实没有财报快照，而不是"代码没接上"
            reasons.put(FactorId.QUAL_ROE, "NO_FINANCIAL_SNAPSHOT_SOURCE");
            reasons.put(FactorId.QUAL_LEV, "NO_FINANCIAL_SNAPSHOT_SOURCE");
            reasons.put(FactorId.LIQ_CURRENT, "NO_FINANCIAL_SNAPSHOT_SOURCE");
        }

        // ── 市值（中性化用）─────────────────────────────────────────────
        double avgAmount = fv.avgAmount20() != null ? fv.avgAmount20() : 0d;
        CrossSectionNeutralizer.LogMvEstimate est =
                CrossSectionNeutralizer.estimateLogMarketCap(last, avgAmount);
        fv.setLogMarketCap(est.logMv(), est.source(), est.reliability());

        return new Outcome(fv, available, reasons);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    private interface DoubleSetter {
        void set(Double v);
    }

    private static void setRet(FeatureVector fv, FactorId id, Set<FactorId> available,
                               Map<FactorId, String> reasons, int n, int required,
                               double lastClose, double[] close, int back, DoubleSetter setter) {
        if (n < required) {
            reasons.put(id, "INSUFFICIENT_BARS");
            return;
        }
        double base = close[n - 1 - back];
        if (Double.isNaN(base) || base <= 0) {
            reasons.put(id, "INVALID_BASE_PRICE");
            return;
        }
        if (Double.isNaN(lastClose) || lastClose <= 0) {
            reasons.put(id, "INVALID_LAST_PRICE");
            return;
        }
        setter.set(ScoringUtils.pct(lastClose, base));
        available.add(id);
    }

    /**
     * 均线多头排列强度：对 ma5/ma10/ma20/ma60 的 6 个两两比较求和并归一到 [-1, 1]。
     *
     * <p>为什么不用现成的 {@code TechnicalIndicators.classifyMaArrangement}（返回字符串）：
     * 字符串无法参与横截面标准化。本函数给出连续取值，保留排序信息。
     * 归一分母 = C(4,2) = 6，即 6 个比较全部同向时取 ±1。
     */
    static double maAlignmentStrength(double ma5, double ma10, double ma20, double ma60) {
        double[] v = {ma5, ma10, ma20, ma60};
        for (double x : v) {
            if (Double.isNaN(x) || x <= 0) {
                return Double.NaN;
            }
        }
        int score = 0;
        int pairs = 0;
        for (int i = 0; i < v.length; i++) {
            for (int j = i + 1; j < v.length; j++) {
                // 周期越短的均线在上（值更大）为多头
                if (v[i] > v[j]) {
                    score++;
                } else if (v[i] < v[j]) {
                    score--;
                }
                pairs++;
            }
        }
        return pairs == 0 ? Double.NaN : (double) score / pairs;
    }

    /**
     * 量价配合方向编码（连续值，供横截面标准化）：
     * 放量上涨 +1、放量下跌 -1、缩量上涨 +0.5、缩量下跌 -0.5、其余 0。
     *
     * <p>阈值与 {@code ScoringConstants.VOLUME_HEAVY_RATIO=1.5} /
     * {@code VOLUME_SHRINK_RATIO=0.7} 一致。任一输入缺失时返回 {@code null}
     * （表示「不可用」，而不是「方向为 0」）。
     */
    static Double volPriceDirection(double volumeRatio, Double ret5) {
        if (ret5 == null || Double.isNaN(volumeRatio)) {
            return null;
        }
        boolean up = ret5 > 0;
        if (volumeRatio >= VOLUME_HEAVY_RATIO) {
            return up ? 1.0 : -1.0;
        }
        if (volumeRatio <= VOLUME_SHRINK_RATIO) {
            return up ? 0.5 : -0.5;
        }
        return 0.0;
    }

    private static boolean allFinite(double[]... arrays) {
        for (double[] a : arrays) {
            for (double v : a) {
                if (Double.isNaN(v)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * BigDecimal → Double，null 透传。
     *
     * <p>与 {@link FinancialSnapshotLoader#toDouble} 保持同一口径（后者是包内可见的 static），
     * 这里复刻而非调用，是为了让本类不反向依赖 loader。
     */
    private static Double toDouble(java.math.BigDecimal v) {
        return FinancialSnapshotLoader.toDouble(v);
    }
}
