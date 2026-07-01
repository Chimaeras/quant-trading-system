package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.entity.StockFinancialSnapshot;
import com.quant.service.scoring.model.CandidateScore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 《3—5 个交易日股票上涨概率评分公式设计文档》v1.0 基线实现。
 * <p>
 * S_raw = (0.55+0.10G)T + 0.20F + (0.25-0.05G)R + 0.05·T·tanh(F)<br>
 * Score = 100 × PercentileRank(S_raw)
 */
@Component
public class CodexShortCycleScoringEngine {

    public static final String FORMULA_VERSION = "codex_short_cycle_v2_neutral";
    public static final int MIN_BARS = 70;
    public static final int MIN_LISTING_DAYS_DEFAULT = 120;
    /** 文档建议入选阈值；可通过请求 minCodexScore=0 关闭 */
    public static final double DEFAULT_MIN_CODEX_SCORE = 90.0;
    public static final double MIN_F_FACTOR = -0.5;
    public static final double MIN_R_FACTOR = -1.0;

    public record StockBarContext(
            String stockCode,
            String exchangeType,
            String stockName,
            String industry,
            List<MarketData> bars,
            StockFinancialSnapshot financialSnapshot
    ) {}

    record RawFeatures(
            String key,
            String stockCode,
            String exchangeType,
            String stockName,
            String industry,
            double er1,
            double er5,
            double er20Minus5,
            double maAlign,
            double rsiShape,
            double volConfirm,
            double roe,
            double revenueYoY,
            double debtRatio,
            double currentRatio,
            double earningsYield,
            double bookToMarket,
            double vol20,
            double downVol20,
            double illiq20,
            double maxGap20,
            double avgAmount20,
            double ret20,
            double logMarketCap,
            String logMvSource
    ) {}

    record ScoredRow(
            RawFeatures raw,
            double tFactor,
            double fFactor,
            double rFactor,
            double sRaw,
            double score,
            Map<String, Object> breakdown
    ) {}

    public List<CandidateScore> scoreUniverse(
            List<StockBarContext> contexts,
            double minAvgAmount,
            boolean excludeST,
            int minListingDays
    ) {
        return scoreUniverse(contexts, minAvgAmount, excludeST, minListingDays, DEFAULT_MIN_CODEX_SCORE);
    }

    public List<CandidateScore> scoreUniverse(
            List<StockBarContext> contexts,
            double minAvgAmount,
            boolean excludeST,
            int minListingDays,
            double minCodexScore
    ) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        int minListing = Math.max(minListingDays, MIN_LISTING_DAYS_DEFAULT);
        List<RawFeatures> raws = new ArrayList<>();
        for (StockBarContext ctx : contexts) {
            Optional<RawFeatures> opt = extractRaw(ctx, minAvgAmount, excludeST, minListing);
            if (opt.isEmpty()) {
                continue;
            }
            RawFeatures rf = opt.get();
            raws.add(rf);
        }
        if (raws.isEmpty()) {
            return List.of();
        }

        List<RawFeatures> adjusted = applyIndustryRelativeReturns(raws);
        List<RawFeatures> preImpute = adjusted;
        adjusted = imputeFinancialByIndustry(adjusted);
        Map<String, Integer> financialImputedByKey = new HashMap<>();
        for (int i = 0; i < preImpute.size() && i < adjusted.size(); i++) {
            financialImputedByKey.put(
                    preImpute.get(i).key(),
                    countImputedFinancialFields(preImpute.get(i), adjusted.get(i)));
        }

        double g = estimateMarketState(adjusted);
        CrossSectionNeutralizer.Context neutCtx = buildNeutralizationContext(adjusted);
        List<ScoredRow> scored = scoreCrossSection(adjusted, g, neutCtx);

        List<CandidateScore> out = new ArrayList<>();
        for (ScoredRow row : scored) {
            if (row.fFactor() <= MIN_F_FACTOR || row.rFactor() <= MIN_R_FACTOR) {
                continue;
            }
            if (minCodexScore > 0 && row.score() < minCodexScore) {
                continue;
            }
            out.add(toCandidate(row, minCodexScore, financialImputedByKey.getOrDefault(row.raw().key(), 0)));
        }
        out.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        return out;
    }

    static List<RawFeatures> applyIndustryRelativeReturns(List<RawFeatures> raws) {
        if (raws == null || raws.isEmpty()) {
            return List.of();
        }
        Map<String, List<RawFeatures>> byInd = new HashMap<>();
        for (RawFeatures rf : raws) {
            String ind = rf.industry() != null && !rf.industry().isBlank() ? rf.industry() : "_none_";
            byInd.computeIfAbsent(ind, k -> new ArrayList<>()).add(rf);
        }
        Map<String, double[]> indMeans = new HashMap<>();
        for (Map.Entry<String, List<RawFeatures>> e : byInd.entrySet()) {
            List<RawFeatures> group = e.getValue();
            double wSum = 0;
            double s1 = 0;
            double s5 = 0;
            double s20 = 0;
            for (RawFeatures rf : group) {
                double w = Math.exp(rf.logMarketCap);
                wSum += w;
                s1 += w * rf.er1;
                s5 += w * rf.er5;
                s20 += w * rf.ret20;
            }
            if (wSum <= 0) {
                indMeans.put(e.getKey(), new double[]{
                        group.stream().mapToDouble(RawFeatures::er1).average().orElse(0),
                        group.stream().mapToDouble(RawFeatures::er5).average().orElse(0),
                        group.stream().mapToDouble(RawFeatures::ret20).average().orElse(0)
                });
            } else {
                indMeans.put(e.getKey(), new double[]{s1 / wSum, s5 / wSum, s20 / wSum});
            }
        }
        List<RawFeatures> out = new ArrayList<>(raws.size());
        for (RawFeatures rf : raws) {
            String ind = rf.industry() != null && !rf.industry().isBlank() ? rf.industry() : "_none_";
            double[] m = indMeans.getOrDefault(ind, new double[3]);
            double er1 = rf.er1 - m[0];
            double er5 = rf.er5 - m[1];
            double er20m5 = (rf.ret20 - m[2]) - (rf.er5 - m[1]);
            out.add(copyRaw(rf, er1, er5, er20m5));
        }
        return out;
    }

    private static RawFeatures copyRaw(RawFeatures rf, double er1, double er5, double er20Minus5) {
        return new RawFeatures(
                rf.key, rf.stockCode, rf.exchangeType, rf.stockName, rf.industry(),
                er1, er5, er20Minus5, rf.maAlign, rf.rsiShape, rf.volConfirm,
                rf.roe, rf.revenueYoY, rf.debtRatio, rf.currentRatio,
                rf.earningsYield, rf.bookToMarket,
                rf.vol20, rf.downVol20, rf.illiq20, rf.maxGap20,
                rf.avgAmount20, rf.ret20, rf.logMarketCap, rf.logMvSource
        );
    }

    static CrossSectionNeutralizer.Context buildNeutralizationContext(List<RawFeatures> raws) {
        List<String> industries = raws.stream()
                .map(r -> r.industry() != null && !r.industry().isBlank() ? r.industry() : "_none_")
                .toList();
        List<Double> logMv = raws.stream().map(RawFeatures::logMarketCap).toList();
        return CrossSectionNeutralizer.buildContext(industries, logMv);
    }

    static List<RawFeatures> imputeFinancialByIndustry(List<RawFeatures> raws) {
        if (raws == null || raws.isEmpty()) {
            return List.of();
        }
        Map<String, List<RawFeatures>> byInd = new HashMap<>();
        for (RawFeatures rf : raws) {
            String ind = rf.industry() != null && !rf.industry().isBlank() ? rf.industry() : "_none_";
            byInd.computeIfAbsent(ind, k -> new ArrayList<>()).add(rf);
        }
        Map<String, double[]> medians = new HashMap<>();
        for (Map.Entry<String, List<RawFeatures>> e : byInd.entrySet()) {
            List<RawFeatures> group = e.getValue();
            medians.put(e.getKey(), new double[]{
                    medianField(group, RawFeatures::roe),
                    medianField(group, RawFeatures::revenueYoY),
                    medianField(group, RawFeatures::debtRatio),
                    medianField(group, RawFeatures::currentRatio),
                    medianField(group, RawFeatures::earningsYield),
                    medianField(group, RawFeatures::bookToMarket)
            });
        }
        List<RawFeatures> out = new ArrayList<>(raws.size());
        for (RawFeatures rf : raws) {
            String ind = rf.industry() != null && !rf.industry().isBlank() ? rf.industry() : "_none_";
            double[] med = medians.getOrDefault(ind, new double[6]);
            double roe = imputeFin(rf.roe, med[0], 0);
            double rev = imputeFin(rf.revenueYoY, med[1], 0);
            double debt = imputeFin(rf.debtRatio, med[2], 50);
            double curr = imputeFin(rf.currentRatio, med[3], 1.5);
            double ey = imputeFin(rf.earningsYield, med[4], 0);
            double bm = imputeFin(rf.bookToMarket, med[5], 0);
            out.add(new RawFeatures(
                    rf.key, rf.stockCode, rf.exchangeType, rf.stockName, rf.industry(),
                    rf.er1, rf.er5, rf.er20Minus5, rf.maAlign, rf.rsiShape, rf.volConfirm,
                    roe, rev, debt, curr, ey, bm,
                    rf.vol20, rf.downVol20, rf.illiq20, rf.maxGap20,
                    rf.avgAmount20, rf.ret20, rf.logMarketCap, rf.logMvSource
            ));
        }
        return out;
    }

    static int countImputedFinancialFields(RawFeatures before, RawFeatures after) {
        int n = 0;
        if (before.roe() != after.roe()) n++;
        if (before.revenueYoY() != after.revenueYoY()) n++;
        if (before.debtRatio() != after.debtRatio()) n++;
        if (before.currentRatio() != after.currentRatio()) n++;
        if (before.earningsYield() != after.earningsYield()) n++;
        if (before.bookToMarket() != after.bookToMarket()) n++;
        return n;
    }

    private static double imputeFin(double value, double industryMedian, double placeholder) {
        if (Math.abs(value - placeholder) < 1e-9 && Math.abs(industryMedian) > 1e-9) {
            return industryMedian;
        }
        if (Math.abs(value) < 1e-9 && Math.abs(industryMedian) > 1e-9) {
            return industryMedian;
        }
        return value;
    }

    private static double medianField(List<RawFeatures> group, java.util.function.ToDoubleFunction<RawFeatures> fn) {
        List<Double> vals = new ArrayList<>();
        for (RawFeatures rf : group) {
            vals.add(fn.applyAsDouble(rf));
        }
        vals.sort(Double::compare);
        int n = vals.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2.0;
    }

    static List<ScoredRow> scoreCrossSection(List<RawFeatures> raws, double g, CrossSectionNeutralizer.Context neutCtx) {
        Map<String, List<Double>> factorCols = new LinkedHashMap<>();
        factorCols.put("er20m5", raws.stream().mapToDouble(RawFeatures::er20Minus5).boxed().toList());
        factorCols.put("er5", raws.stream().mapToDouble(RawFeatures::er5).boxed().toList());
        factorCols.put("er1", raws.stream().mapToDouble(RawFeatures::er1).boxed().toList());
        factorCols.put("maAlign", raws.stream().mapToDouble(RawFeatures::maAlign).boxed().toList());
        factorCols.put("rsiShape", raws.stream().mapToDouble(RawFeatures::rsiShape).boxed().toList());
        factorCols.put("volConfirm", raws.stream().mapToDouble(RawFeatures::volConfirm).boxed().toList());
        factorCols.put("roe", raws.stream().mapToDouble(RawFeatures::roe).boxed().toList());
        factorCols.put("revenueYoY", raws.stream().mapToDouble(RawFeatures::revenueYoY).boxed().toList());
        factorCols.put("debtRatio", raws.stream().mapToDouble(RawFeatures::debtRatio).boxed().toList());
        factorCols.put("currentRatio", raws.stream().mapToDouble(RawFeatures::currentRatio).boxed().toList());
        factorCols.put("earningsYield", raws.stream().mapToDouble(RawFeatures::earningsYield).boxed().toList());
        factorCols.put("bookToMarket", raws.stream().mapToDouble(RawFeatures::bookToMarket).boxed().toList());
        factorCols.put("vol20", raws.stream().mapToDouble(RawFeatures::vol20).boxed().toList());
        factorCols.put("downVol20", raws.stream().mapToDouble(RawFeatures::downVol20).boxed().toList());
        factorCols.put("illiq20", raws.stream().mapToDouble(RawFeatures::illiq20).boxed().toList());
        factorCols.put("maxGap20", raws.stream().mapToDouble(RawFeatures::maxGap20).boxed().toList());

        Map<String, List<Double>> zByFactor = zNeutralizedBatch(factorCols, neutCtx);
        List<Double> zEr20m5 = zByFactor.get("er20m5");
        List<Double> zEr5 = zByFactor.get("er5");
        List<Double> zEr1 = zByFactor.get("er1");
        List<Double> zMa = zByFactor.get("maAlign");
        List<Double> zRsi = zByFactor.get("rsiShape");
        List<Double> zVolC = zByFactor.get("volConfirm");
        List<Double> zRoe = zByFactor.get("roe");
        List<Double> zRev = zByFactor.get("revenueYoY");
        List<Double> zDebt = zByFactor.get("debtRatio");
        List<Double> zCurr = zByFactor.get("currentRatio");
        List<Double> zEy = zByFactor.get("earningsYield");
        List<Double> zBm = zByFactor.get("bookToMarket");
        List<Double> zVol20 = zByFactor.get("vol20");
        List<Double> zDown = zByFactor.get("downVol20");
        List<Double> zIlliq = zByFactor.get("illiq20");
        List<Double> zGap = zByFactor.get("maxGap20");

        List<ScoredRow> rows = new ArrayList<>(raws.size());
        for (int i = 0; i < raws.size(); i++) {
            double t = 0.28 * zEr20m5.get(i) + 0.20 * zEr5.get(i) - 0.15 * zEr1.get(i)
                    + 0.17 * zMa.get(i) + 0.10 * zRsi.get(i) + 0.10 * zVolC.get(i);
            double quality = 0.40 * zRoe.get(i) + 0.25 * zRev.get(i) - 0.20 * zDebt.get(i) + 0.15 * zCurr.get(i);
            double value = 0.60 * zEy.get(i) + 0.40 * zBm.get(i);
            double f = 0.75 * quality + 0.25 * value;
            double r = -0.35 * zVol20.get(i) - 0.25 * zDown.get(i) - 0.25 * zIlliq.get(i) - 0.15 * zGap.get(i);
            double sRaw = (0.55 + 0.10 * g) * t + 0.20 * f + (0.25 - 0.05 * g) * r + 0.05 * t * Math.tanh(f);
            Map<String, Object> bd = new LinkedHashMap<>();
            bd.put("formula_version", FORMULA_VERSION);
            bd.put("neutralization_mode", neutCtx != null ? neutCtx.mode() : CrossSectionNeutralizer.MODE_PASSTHROUGH);
            bd.put("neutralization_industries", neutCtx != null ? neutCtx.industryCount() : 0);
            bd.put("preprocessing", "winsorize→OLS(lnMV+industry)→robustZ(MAD,no二次winsorize)");
            bd.put("G_market", ScoringUtils.round4(g));
            bd.put("T", ScoringUtils.round4(t));
            bd.put("F", ScoringUtils.round4(f));
            bd.put("R", ScoringUtils.round4(r));
            bd.put("S_raw", ScoringUtils.round4(sRaw));
            rows.add(new ScoredRow(raws.get(i), t, f, r, sRaw, 0, bd));
        }

        List<Double> sRaws = rows.stream().map(ScoredRow::sRaw).toList();
        List<Double> pctScores = CrossSectionFactorMath.percentileScores(sRaws);
        List<ScoredRow> finalRows = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            ScoredRow row = rows.get(i);
            row.breakdown().put("score_percentile", ScoringUtils.round2(pctScores.get(i)));
            finalRows.add(new ScoredRow(row.raw(), row.tFactor(), row.fFactor(), row.rFactor(), row.sRaw(),
                    pctScores.get(i), row.breakdown()));
        }
        return finalRows;
    }

    private static Map<String, List<Double>> zNeutralizedBatch(
            Map<String, List<Double>> factorCols,
            CrossSectionNeutralizer.Context neutCtx
    ) {
        Map<String, CrossSectionNeutralizer.Result> neutralized =
                CrossSectionNeutralizer.neutralizeBatch(factorCols, neutCtx);
        Map<String, List<Double>> z = new LinkedHashMap<>();
        for (Map.Entry<String, CrossSectionNeutralizer.Result> e : neutralized.entrySet()) {
            z.put(e.getKey(), CrossSectionFactorMath.robustZScoresOnResiduals(e.getValue().residuals()));
        }
        return z;
    }

    private static List<Double> zNeutralized(
            List<RawFeatures> raws,
            java.util.function.ToDoubleFunction<RawFeatures> fn,
            CrossSectionNeutralizer.Context neutCtx
    ) {
        List<Double> vals = raws.stream().mapToDouble(fn).boxed().toList();
        CrossSectionNeutralizer.Result res = CrossSectionNeutralizer.neutralize(vals, neutCtx);
        return CrossSectionFactorMath.robustZScoresOnResiduals(res.residuals());
    }

    static double estimateMarketState(List<RawFeatures> raws) {
        double avgEr20m5 = raws.stream().mapToDouble(r -> r.er20Minus5).average().orElse(0);
        double avgMa = raws.stream().mapToDouble(r -> r.maAlign).average().orElse(0);
        double avgVol = raws.stream().mapToDouble(r -> r.vol20).average().orElse(0.2);
        double atrProxy = Math.max(0.05, avgVol);
        return 0.5 * Math.tanh(avgEr20m5 / atrProxy) + 0.5 * Math.tanh(avgMa / 0.05);
    }

    Optional<RawFeatures> extractRaw(
            StockBarContext ctx,
            double minAvgAmount,
            boolean excludeST,
            int minListingDays
    ) {
        List<MarketData> bars = ctx.bars();
        if (bars == null || bars.size() < MIN_BARS) {
            return Optional.empty();
        }
        if (!ScoringUtils.isTradableForScoring(
                bars, ctx.stockCode(), ctx.exchangeType(), ctx.stockName(), excludeST, true, true)) {
            return Optional.empty();
        }

        List<Double> close = new ArrayList<>();
        List<Double> amount = new ArrayList<>();
        List<Double> open = new ArrayList<>();
        for (MarketData b : bars) {
            if (b.getClosePrice() == null || b.getClosePrice().doubleValue() <= 0) {
                continue;
            }
            close.add(b.getClosePrice().doubleValue());
            amount.add(b.getAmount() != null ? b.getAmount().doubleValue() : 0);
            open.add(b.getOpenPrice() != null ? b.getOpenPrice().doubleValue() : b.getClosePrice().doubleValue());
        }
        if (close.size() < MIN_BARS) {
            return Optional.empty();
        }
        int n = close.size();
        double avgAmount20 = TechnicalIndicators.avg(amount, n - 20, n - 1);
        if (avgAmount20 < minAvgAmount) {
            return Optional.empty();
        }

        double ret1 = logRet(close, 1);
        double ret5 = logRet(close, 5);
        double ret20 = logRet(close, 20);
        MarketData last = bars.get(bars.size() - 1);
        CrossSectionNeutralizer.LogMvEstimate logMvEst =
                CrossSectionNeutralizer.estimateLogMarketCap(last, avgAmount20);
        double logMv = logMvEst.logMv();

        double ma5 = TechnicalIndicators.avg(close, n - 5, n - 1);
        double ma10 = TechnicalIndicators.avg(close, n - 10, n - 1);
        double ma20 = TechnicalIndicators.avg(close, n - 20, n - 1);
        double price = close.get(n - 1);
        double maAlign = safeRatio(price, ma5) - 1 + safeRatio(ma5, ma10) - 1 + safeRatio(ma10, ma20) - 1;

        double[] closeArr = close.stream().mapToDouble(Double::doubleValue).toArray();
        double rsi14 = TechnicalIndicators.rsiAt(closeArr, closeArr.length - 1, ScoringConstants.RSI_PERIOD);
        double rsiShape = -Math.pow((rsi14 - 62) / 18.0, 2);

        List<Double> amtHist = amount.subList(Math.max(0, n - 21), n - 1);
        double medAmt = medianList(amtHist);
        double volShock = medAmt > 0 ? Math.log(Math.max(1e-9, amount.get(n - 1) / medAmt)) : 0;
        double volConfirm = Math.signum(ret5) * volShock;

        Double pe = last.getPeRatio() != null ? last.getPeRatio().doubleValue() : null;
        Double pb = last.getPbRatio() != null ? last.getPbRatio().doubleValue() : null;
        StockFinancialSnapshot fin = ctx.financialSnapshot();
        if (fin != null) {
            if (pb == null && fin.getPbRatio() != null) {
                pb = fin.getPbRatio().doubleValue();
            }
        }
        double roe = fin != null && fin.getRoe() != null ? fin.getRoe().doubleValue() : 0;
        double revenueYoY = fin != null && fin.getRevenueYoY() != null ? fin.getRevenueYoY().doubleValue() : 0;
        double debtRatio = fin != null && fin.getDebtRatio() != null ? fin.getDebtRatio().doubleValue() : 50;
        double currentRatio = fin != null && fin.getCurrentRatio() != null ? fin.getCurrentRatio().doubleValue() : 1.5;
        double earningsYield = pe != null && pe > 0 ? 1.0 / pe : 0;
        double bookToMarket = pb != null && pb > 0 ? 1.0 / pb : 0;

        double vol20 = annualizedVol(close, 20);
        double downVol20 = downVol(close, 20);
        double illiq20 = illiquidity(close, amount, 20);
        double maxGap20 = maxGap(open, close, 20);

        String key = ctx.stockCode() + "|" + ctx.exchangeType();
        String industry = ctx.industry() != null ? ctx.industry() : "";
        return Optional.of(new RawFeatures(
                key, ctx.stockCode(), ctx.exchangeType(), ctx.stockName(), industry,
                ret1, ret5, 0, maAlign, rsiShape, volConfirm,
                roe, revenueYoY, debtRatio, currentRatio, earningsYield, bookToMarket,
                vol20, downVol20, illiq20, maxGap20, avgAmount20, ret20, logMv, logMvEst.source()
        ));
    }

    private static CandidateScore toCandidate(ScoredRow row, double minCodexScoreApplied, int financialImputedCount) {
        CandidateScore c = new CandidateScore();
        c.setStockCode(row.raw().stockCode());
        c.setExchangeType(row.raw().exchangeType());
        c.setStockName(row.raw().stockName());
        c.setFinalScore(row.score());
        c.setTrendScore(ScoringUtils.clamp(row.tFactor() * 10 + 50, 0, 100));
        c.setMomentumScore(ScoringUtils.clamp(row.fFactor() * 10 + 50, 0, 100));
        c.setVolumeScore(ScoringUtils.clamp(row.rFactor() * 10 + 50, 0, 100));
        c.setRiskPenalty(ScoringUtils.clamp(-row.rFactor() * 10, 0, 100));
        c.setAvgAmount20(row.raw().avgAmount20());
        c.setPassMinAvgAmount(true);
        c.setPassMinTrendScore(true);
        c.setPassMaTrend(true);
        c.setPassRet20(true);
        Map<String, Object> reasons = new LinkedHashMap<>(row.breakdown());
        reasons.put("horizon_profile", StrategyFormulaRegistry.HORIZON_SHORT_3_5D);
        reasons.put("scoring_model", FORMULA_VERSION);
        reasons.put("doc_ref", "Codex/短周期选股评分公式设计文档.md v1.0");
        reasons.put("log_mv_source", row.raw().logMvSource());
        reasons.put("log_market_cap", ScoringUtils.round4(row.raw().logMarketCap()));
        if (financialImputedCount > 0) {
            reasons.put("financial_imputed_count", financialImputedCount);
        }
        if (minCodexScoreApplied > 0) {
            reasons.put("min_codex_score_applied", ScoringUtils.round2(minCodexScoreApplied));
        }
        c.setReasons(reasons);
        return c;
    }

    private static double logRet(List<Double> close, int lag) {
        int n = close.size();
        if (n <= lag) {
            return 0;
        }
        double prev = close.get(n - 1 - lag);
        if (prev <= 0) {
            return 0;
        }
        return Math.log(close.get(n - 1) / prev);
    }

    private static double safeRatio(double a, double b) {
        return b != 0 ? a / b : 1;
    }

    private static double medianList(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> s = new ArrayList<>(values);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static double annualizedVol(List<Double> close, int window) {
        int n = close.size();
        if (n <= window) {
            return 0;
        }
        List<Double> rets = new ArrayList<>();
        for (int i = n - window; i < n; i++) {
            if (close.get(i - 1) > 0) {
                rets.add(Math.log(close.get(i) / close.get(i - 1)));
            }
        }
        if (rets.isEmpty()) {
            return 0;
        }
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(var) * Math.sqrt(252);
    }

    private static double downVol(List<Double> close, int window) {
        int n = close.size();
        List<Double> downs = new ArrayList<>();
        for (int i = n - window; i < n; i++) {
            if (i <= 0 || close.get(i - 1) <= 0) {
                continue;
            }
            double r = Math.log(close.get(i) / close.get(i - 1));
            downs.add(Math.min(r, 0));
        }
        if (downs.isEmpty()) {
            return 0;
        }
        double mean = downs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.sqrt(downs.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
    }

    private static double illiquidity(List<Double> close, List<Double> amount, int window) {
        int n = close.size();
        double sum = 0;
        int cnt = 0;
        for (int i = n - window; i < n; i++) {
            if (i <= 0 || close.get(i - 1) <= 0) {
                continue;
            }
            double r = Math.abs(Math.log(close.get(i) / close.get(i - 1)));
            double amt = Math.max(1, amount.get(i));
            sum += r / amt;
            cnt++;
        }
        return cnt == 0 ? 0 : sum / cnt;
    }

    private static double maxGap(List<Double> open, List<Double> close, int window) {
        int n = close.size();
        double max = 0;
        for (int i = n - window; i < n; i++) {
            if (i <= 0 || close.get(i - 1) <= 0) {
                continue;
            }
            max = Math.max(max, Math.abs(open.get(i) / close.get(i - 1) - 1));
        }
        return max;
    }
}
