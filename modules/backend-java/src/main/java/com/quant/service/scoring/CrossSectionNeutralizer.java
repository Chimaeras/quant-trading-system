package com.quant.service.scoring;

import com.quant.entity.MarketData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设计文档 §4.1：缩尾后对因子做 ln(市值)+行业哑变量横截面回归，取残差 ε。
 * <p>
 * x = a + b·ln(MV) + Σ c_j·Industry_j + ε
 */
public final class CrossSectionNeutralizer {

    public static final int MIN_SAMPLE_FOR_FULL_REGRESSION = 30;
    public static final int MIN_INDUSTRIES_FOR_REGRESSION = 3;
    public static final double RIDGE_LAMBDA = 1e-4;

    public static final String MODE_FULL = "FULL_MV_INDUSTRY_REGRESSION";
    public static final String MODE_LOG_MV_ONLY = "LOG_MV_ONLY_REGRESSION";
    public static final String MODE_INDUSTRY_DEMEAN = "INDUSTRY_DEMEAN_FALLBACK";
    public static final String MODE_PASSTHROUGH = "PASSTHROUGH";

    private CrossSectionNeutralizer() {}

    public record Context(
            int n,
            String mode,
            int industryCount,
            double[][] xTx,
            List<Double> logMv,
            List<String> industries,
            Map<String, Integer> industryToCol
    ) {}

    public record Result(List<Double> residuals, String mode, int industryCount) {}

    public record LogMvEstimate(double logMv, String source) {}

    public static final String LOG_MV_FROM_TURNOVER = "turnover_amount";
    public static final String LOG_MV_FROM_NOTIONAL = "close_x_volume";
    public static final String LOG_MV_FROM_AVG_AMOUNT = "avg_amount20_fallback";

    public static Context buildContext(List<String> industries, List<Double> logMarketCap) {
        int n = industries != null ? industries.size() : 0;
        if (n == 0) {
            return new Context(0, MODE_PASSTHROUGH, 0, new double[0][0], List.of(), List.of(), Map.of());
        }
        List<String> inds = normalizeIndustries(industries);
        List<Double> logMv = normalizeLogMv(logMarketCap, n);
        Set<String> uniqueInd = new LinkedHashSet<>(inds);
        int industryCount = uniqueInd.size();

        if (n < MIN_SAMPLE_FOR_FULL_REGRESSION) {
            return new Context(n, MODE_INDUSTRY_DEMEAN, industryCount, null, logMv, inds, Map.of());
        }
        if (industryCount < MIN_INDUSTRIES_FOR_REGRESSION) {
            return buildLogMvOnlyContext(n, industryCount, logMv, inds);
        }

        List<String> dummyIndustries = pickDummyIndustries(uniqueInd);
        Map<String, Integer> indToCol = new LinkedHashMap<>();
        for (int j = 0; j < dummyIndustries.size(); j++) {
            indToCol.put(dummyIndustries.get(j), j);
        }
        int p = 2 + dummyIndustries.size(); // intercept + logMv + dummies
        double[][] xTx = new double[p][p];

        for (int i = 0; i < n; i++) {
            double[] row = designRow(inds.get(i), logMv.get(i), indToCol, p);
            for (int a = 0; a < p; a++) {
                for (int b = a; b < p; b++) {
                    xTx[a][b] += row[a] * row[b];
                }
            }
        }
        symmetrize(xTx);
        for (int i = 0; i < p; i++) {
            xTx[i][i] += RIDGE_LAMBDA;
        }
        return new Context(n, MODE_FULL, industryCount, xTx, logMv, inds, indToCol);
    }

    private static Context buildLogMvOnlyContext(int n, int industryCount, List<Double> logMv, List<String> inds) {
        int p = 2; // intercept + logMv
        double[][] xTx = new double[p][p];
        for (int i = 0; i < n; i++) {
            double[] row = {1.0, logMv.get(i)};
            for (int a = 0; a < p; a++) {
                for (int b = a; b < p; b++) {
                    xTx[a][b] += row[a] * row[b];
                }
            }
        }
        symmetrize(xTx);
        for (int i = 0; i < p; i++) {
            xTx[i][i] += RIDGE_LAMBDA;
        }
        return new Context(n, MODE_LOG_MV_ONLY, industryCount, xTx, logMv, inds, Map.of());
    }

    public static Result neutralize(List<Double> values, Context ctx) {
        if (values == null || values.isEmpty() || ctx == null || ctx.n() == 0) {
            return new Result(values != null ? values : List.of(), MODE_PASSTHROUGH, 0);
        }
        int n = ctx.n();
        if (values.size() != n) {
            throw new IllegalArgumentException("values size " + values.size() + " != context n " + n);
        }
        List<Double> winsorized = CrossSectionFactorMath.winsorize(values, 2.5, 97.5);

        if (MODE_INDUSTRY_DEMEAN.equals(ctx.mode())) {
            return new Result(industryDemean(winsorized, ctx.industries(), ctx.logMv()), ctx.mode(), ctx.industryCount());
        }
        if (MODE_PASSTHROUGH.equals(ctx.mode())) {
            return new Result(winsorized, ctx.mode(), ctx.industryCount());
        }

        int p = ctx.xTx().length;
        double[] xTy = new double[p];
        for (int i = 0; i < n; i++) {
            double y = winsorized.get(i);
            double[] row = designRow(ctx.industries().get(i), ctx.logMv().get(i), ctx.industryToCol(), p);
            for (int a = 0; a < p; a++) {
                xTy[a] += row[a] * y;
            }
        }

        double[] beta = solveSymmetric(ctx.xTx(), xTy);
        if (beta == null) {
            return new Result(industryDemean(winsorized, ctx.industries(), ctx.logMv()), MODE_INDUSTRY_DEMEAN, ctx.industryCount());
        }

        List<Double> residuals = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double[] row = designRow(ctx.industries().get(i), ctx.logMv().get(i), ctx.industryToCol(), p);
            double fit = 0;
            for (int a = 0; a < p; a++) {
                fit += row[a] * beta[a];
            }
            residuals.add(winsorized.get(i) - fit);
        }
        return new Result(residuals, ctx.mode(), ctx.industryCount());
    }

    /** 同一 Context 下批量中性化多列因子，避免重复构建 X'X。 */
    public static Map<String, Result> neutralizeBatch(Map<String, List<Double>> factorColumns, Context ctx) {
        Map<String, Result> out = new LinkedHashMap<>();
        if (factorColumns == null) {
            return out;
        }
        for (Map.Entry<String, List<Double>> e : factorColumns.entrySet()) {
            out.put(e.getKey(), neutralize(e.getValue(), ctx));
        }
        return out;
    }

    /** winsorize → 中性化回归 → 残差稳健 Z（文档 §4.1 完整链路）。 */
    public static List<Double> neutralizedRobustZScores(
            List<Double> values,
            List<String> industries,
            List<Double> logMarketCap
    ) {
        Context ctx = buildContext(industries, logMarketCap);
        Result res = neutralize(values, ctx);
        return CrossSectionFactorMath.robustZScoresOnResiduals(res.residuals());
    }

    public static LogMvEstimate estimateLogMarketCap(MarketData lastBar, double avgAmount20) {
        if (lastBar != null && lastBar.getTurnoverRate() != null && lastBar.getAmount() != null) {
            double tr = lastBar.getTurnoverRate().doubleValue();
            double amt = lastBar.getAmount().doubleValue();
            if (tr > 1e-8 && amt > 0) {
                double trFrac = tr > 1.0 ? tr / 100.0 : tr;
                double mv = amt / Math.max(trFrac, 1e-9);
                return new LogMvEstimate(Math.log(Math.max(mv, 1.0)), LOG_MV_FROM_TURNOVER);
            }
        }
        if (lastBar != null && lastBar.getClosePrice() != null && lastBar.getVolume() != null
                && lastBar.getVolume() > 0) {
            double notional = lastBar.getClosePrice().doubleValue() * lastBar.getVolume();
            return new LogMvEstimate(Math.log(Math.max(notional, 1.0)), LOG_MV_FROM_NOTIONAL);
        }
        return new LogMvEstimate(Math.log(Math.max(avgAmount20, 1.0)), LOG_MV_FROM_AVG_AMOUNT);
    }

    public static double logMarketCapProxy(MarketData lastBar, double avgAmount20) {
        return estimateLogMarketCap(lastBar, avgAmount20).logMv();
    }

    private static List<String> normalizeIndustries(List<String> industries) {
        List<String> out = new ArrayList<>(industries.size());
        for (String ind : industries) {
            out.add(ind != null && !ind.isBlank() ? ind.trim() : "_none_");
        }
        return out;
    }

    private static List<Double> normalizeLogMv(List<Double> logMarketCap, int n) {
        List<Double> out = new ArrayList<>(n);
        List<Double> finite = new ArrayList<>();
        if (logMarketCap != null) {
            for (Double v : logMarketCap) {
                if (v != null && Double.isFinite(v)) {
                    finite.add(v);
                }
            }
        }
        finite.sort(Double::compare);
        double fallback = finite.isEmpty()
                ? Math.log(1e8)
                : finite.get(finite.size() / 2);
        for (int i = 0; i < n; i++) {
            if (logMarketCap != null && i < logMarketCap.size() && logMarketCap.get(i) != null
                    && Double.isFinite(logMarketCap.get(i))) {
                out.add(logMarketCap.get(i));
            } else {
                out.add(fallback);
            }
        }
        return out;
    }

    private static List<String> pickDummyIndustries(Set<String> uniqueInd) {
        List<String> sorted = new ArrayList<>(uniqueInd);
        sorted.sort(String::compareTo);
        if (sorted.size() <= 1) {
            return List.of();
        }
        return sorted.subList(1, sorted.size());
    }

    private static double[] designRow(String industry, double logMv, Map<String, Integer> indToCol, int p) {
        double[] row = new double[p];
        row[0] = 1.0;
        row[1] = logMv;
        Integer col = indToCol.get(industry);
        if (col != null) {
            row[2 + col] = 1.0;
        }
        return row;
    }

    private static void symmetrize(double[][] m) {
        int p = m.length;
        for (int i = 0; i < p; i++) {
            for (int j = i + 1; j < p; j++) {
                m[j][i] = m[i][j];
            }
        }
    }

    private static List<Double> industryDemean(List<Double> values, List<String> industries, List<Double> logMv) {
        Map<String, Double> wSum = new LinkedHashMap<>();
        Map<String, Double> wVal = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            String ind = industries.get(i);
            double w = logMv != null && i < logMv.size() ? Math.exp(logMv.get(i)) : 1.0;
            wSum.merge(ind, w, Double::sum);
            wVal.merge(ind, w * values.get(i), Double::sum);
        }
        Map<String, Double> means = new LinkedHashMap<>();
        for (String ind : wSum.keySet()) {
            double ws = wSum.get(ind);
            means.put(ind, ws > 0 ? wVal.get(ind) / ws : 0.0);
        }
        List<Double> out = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            out.add(values.get(i) - means.getOrDefault(industries.get(i), 0.0));
        }
        return out;
    }

    /** 解对称正定（或 ridge 正则）线性方程组。 */
    static double[] solveSymmetric(double[][] a, double[] b) {
        int n = a.length;
        if (n == 0 || b.length != n) {
            return null;
        }
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                return null;
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            double div = m[col][col];
            for (int j = col; j <= n; j++) {
                m[col][j] /= div;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double factor = m[r][col];
                for (int j = col; j <= n; j++) {
                    m[r][j] -= factor * m[col][j];
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = m[i][n];
        }
        return x;
    }
}
