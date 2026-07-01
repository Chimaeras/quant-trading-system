package com.quant.service.scoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各选股策略公式说明与短周期（T+3～T+5）预测适配性审计。
 */
public final class StrategyFormulaRegistry {

    public static final String HORIZON_SHORT_3_5D = "SHORT_3_5D";
    public static final String HORIZON_MEDIUM_TREND = "MEDIUM_TREND";

    private StrategyFormulaRegistry() {}

    public record FormulaAudit(
            String strategyType,
            String factorVersion,
            String horizonProfile,
            String formulaSummary,
            String finalScoreFormula,
            List<String> subFormulas,
            int shortHorizonFitScore,
            String shortHorizonVerdict,
            List<String> caveats,
            List<String> usageTips
    ) {}

    public static FormulaAudit audit(String strategyType, String factorVersion, String horizonProfile) {
        String st = strategyType != null ? strategyType : "UNKNOWN";
        String fv = factorVersion != null && !factorVersion.isBlank() ? factorVersion : "default";
        String hp = horizonProfile != null && !horizonProfile.isBlank() ? horizonProfile : HORIZON_MEDIUM_TREND;
        return switch (st) {
            case "CLASSIC_SELECTOR" -> auditClassic(fv, hp);
            case "OVERSOLD_REBOUND" -> auditOversold(fv);
            case "TREND_SCORING", "STOCK_TREND" -> auditTrend(fv);
            case "MULTI_DIM" -> auditMultiDim(fv);
            default -> auditGeneric(st, fv);
        };
    }

    public static Map<String, Object> toMap(FormulaAudit audit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy_type", audit.strategyType());
        m.put("factor_version", audit.factorVersion());
        m.put("horizon_profile", audit.horizonProfile());
        m.put("formula_summary", audit.formulaSummary());
        m.put("final_score_formula", audit.finalScoreFormula());
        m.put("sub_formulas", audit.subFormulas());
        m.put("short_horizon_fit_score", audit.shortHorizonFitScore());
        m.put("short_horizon_verdict", audit.shortHorizonVerdict());
        m.put("caveats", audit.caveats());
        m.put("usage_tips", audit.usageTips());
        return m;
    }

    private static FormulaAudit auditClassic(String fv, String horizonProfile) {
        boolean shortProfile = HORIZON_SHORT_3_5D.equals(horizonProfile);
        boolean codex = shortProfile && (fv == null || fv.contains("codex") || fv.contains("short_cycle"));
        boolean neutralV2 = codex && fv != null && fv.contains("v2_neutral");
        if (shortProfile) {
            if (codex) {
                return new FormulaAudit(
                        "CLASSIC_SELECTOR",
                        fv,
                        HORIZON_SHORT_3_5D,
                        neutralV2
                                ? "Codex v2：完整 ln(MV)+行业 OLS 中性化后 T/F/R/G 合成"
                                : "Codex 短周期 T/F/R/G：趋势反转 + 质量估值 + 风险惩罚，市场状态 G 动态加权",
                        "S_raw=(0.55+0.10G)T+0.20F+(0.25-0.05G)R+0.05·T·tanh(F); Score=100×PercentileRank(S_raw)",
                        List.of(
                                "预处理：2.5/97.5%缩尾 → ε=OLS(x~1+lnMV+Industry) → Z(MAD)",
                                "ER：市值加权行业指数收益（§5.1）",
                                "T=0.28Z(ER20−ER5)+0.20Z(ER5)−0.15Z(ER1)+0.17Z(MAAlign)+0.10Z(RSIShape)+0.10Z(VolConfirm)",
                                "F=0.75Quality+0.25Value; R=−0.35Z(Vol20)−0.25Z(DownVol)−0.25Z(Illiq)−0.15Z(MaxGap)",
                                "硬过滤：F>−0.5 且 R>−1.0；建议 Score≥90 入选"
                        ),
                        neutralV2 ? 95 : 92,
                        neutralV2
                                ? "§4.1 完整横截面回归中性化已实现；MV 无字段时用成交额/换手率代理 ln(MV)"
                                : "与《短周期选股评分公式设计文档》v1.0 对齐，适合 T+3/T+5 横截面排序",
                        List.of(
                                neutralV2 ? "ln(MV) 来自 turnover/amount 或 avgAmount20 代理" : "G 使用全市场代理而非真实指数 MA/ATR",
                                neutralV2 ? "样本<30 或行业<3 时回退行业 demean" : "行业中性化为同行业 ER5 均值调整",
                                "Score 为百分位分，高分=相对更强而非绝对上涨"
                        ),
                        List.of(
                                "选股设置 horizonProfile=SHORT_3_5D（自动使用 " + CodexShortCycleScoringEngine.FORMULA_VERSION + "）",
                                "回测重点看 T+3/T+5 Rank IC 与 G5-G1 spread",
                                "查看 reasons.neutralization_mode 确认回归模式"
                        )
                );
            }
            return new FormulaAudit(
                    "CLASSIC_SELECTOR",
                    fv,
                    HORIZON_SHORT_3_5D,
                    "短周期经典因子：3/5日动量 + 超卖修复 + 量能确认 - 波动风险",
                    "final = 0.40×shortHorizon + 0.25×momentum + 0.20×volume - 0.15×risk",
                    List.of(
                            "shortHorizon = ret3/5动量(35) + RSI修复(35) + MACD柱拐头(30)",
                            "momentum = RSI偏离 + MACD柱比 + 量比",
                            "volume = 20日均额流动性 + 当日量比",
                            "risk = 20日波动 + 60日最大回撤",
                            "门槛：流动性 + shortHorizon≥35；不再强制 MA20>MA60"
                    ),
                    82,
                    "较适合 T+3～T+5：侧重短期动量与超卖修复，应用 IC 在 T+3/T+5 验证",
                    List.of(
                            "短周期模式放弃中长期趋势门槛，截面 Z-Score 后排名",
                            "仍无法保证上涨概率，需结合回测 IC 与分组收益",
                            "幸存者偏差与可交易性过滤仍影响实盘可复现性"
                    ),
                    List.of(
                            "选股请求设置 horizonProfile=SHORT_3_5D",
                            "回测重点看 T+3、T+5 的 Rank IC 与 G5-G1 spread",
                            "高分=相对更强，不等于绝对上涨"
                    )
            );
        }
        return new FormulaAudit(
                "CLASSIC_SELECTOR",
                fv,
                HORIZON_MEDIUM_TREND,
                "中长期趋势因子：趋势 + 动量 + 量价 - 风险（默认）",
                "final = w_trend×trend + w_momentum×momentum + w_volume×volume - w_risk×risk",
                List.of(
                        "trend = MA20>MA60(35) + ret20 + ret60×0.5",
                        "momentum = (RSI-40)×1.5 + MACD柱/价×40000 + 量比×12",
                        "volume = avgAmount20/minAvgAmount×60 + volumeRatio×20",
                        "risk = vol20×1.5 + mdd60×1.2",
                        "硬门槛：avgAmount20≥min、MA20>MA60、ret20≥-5、trend≥minTrend"
                ),
                45,
                "默认模式偏 20～60 日趋势，不宜直接用于「3～5 日内必涨」筛选",
                List.of(
                        "注释写 40/25/20/15，YAML 默认 0.35/0.20/0.20/0.25，以运行配置为准",
                        "趋势门槛会排除大量超跌反弹候选",
                        "截面 Z-Score 改变绝对分含义，仅适合同日横向比较"
                ),
                List.of(
                        "若目标为 3～5 日上涨，请切换 horizonProfile=SHORT_3_5D",
                        "或用 OVERSOLD_REBOUND 策略并验证 T+3/T+5 IC",
                        "勿将 medium 模式 TopN 等同于短周期 alpha"
                )
        );
    }

    private static FormulaAudit auditOversold(String fv) {
        return new FormulaAudit(
                "OVERSOLD_REBOUND",
                fv,
                HORIZON_SHORT_3_5D,
                "超跌反弹：趋势弱化(40) + 量能(30) + 超跌(30) + 确认(15)，无确认封顶70",
                "total = 35%×(trend/40) + 25%×(volume/30) + 25%×(oversold/30) + 15%×(confirm/15)",
                List.of(
                        "trend = ret60/120 弱势 + 价格低于均线 + 连续阴线周",
                        "volume = 5/30 量比 + 下跌放量/缩量再放量",
                        "oversold = RSI + 布林下轨 + 1年分位",
                        "confirm = MACD底背离 + 量能确认 + 锤子/早晨之星"
                ),
                88,
                "公式设计与 3～5 日反弹逻辑一致，优先验证该策略 T+3/T+5 IC",
                List.of(
                        "无确认信号时总分≤70，可能漏掉滞后确认标的",
                        "超跌不等于立即反弹，需结合大盘环境"
                ),
                List.of(
                        "筛选 minTotalScore≥60 且 confirm_has_signal=true 的子集可提高精度",
                        "回测对比 OVERSOLD vs CLASSIC 在 T+3/T+5 的 ICIR"
                )
        );
    }

    private static FormulaAudit auditTrend(String fv) {
        return new FormulaAudit(
                "TREND_SCORING",
                fv,
                HORIZON_MEDIUM_TREND,
                "趋势交易多因子加总（约100分）：趋势22+乖离18+量价10+支撑5+MACD10+RSI8+换手5+Δ指标10+…",
                "total = Σ scoreTrend + scoreBias + scoreVolume + scoreSupport + scoreMacd + scoreRsi + scoreTurnover + scoreDelta + …",
                List.of(
                        "scoreDelta = ΔRSI + ΔMACD 柱，对 1～3 日转折较敏感",
                        "scoreTrend/scoreBias 偏 5～20 日趋势延续",
                        "scoreConsecutiveVol / scoreVolConv 辅助短周期量价"
                ),
                62,
                "部分子因子（ΔRSI/ΔMACD/量价）对 3～5 日有帮助，但整体仍偏趋势持有",
                List.of(
                        "强势多头下允许更高乖离，短周期追高风险上升",
                        "需分因子做 IC 分解才能判断哪一项驱动短周期收益"
                ),
                List.of(
                        "短周期可优先看 scoreDelta 与 scoreConsecutiveVol 高的标的",
                        "回测时按 trend 策略类型单独看 T+3/T+5 IC"
                )
        );
    }

    private static FormulaAudit auditMultiDim(String fv) {
        return new FormulaAudit(
                "MULTI_DIM",
                fv,
                HORIZON_MEDIUM_TREND,
                "技术面 + 行业PE + 板块强度 + 行业相对收益，动态权重合成",
                "composite = w_tech×tech + w_fund×fund + w_sent×sent + industry_adj",
                List.of(
                        "tech 来自 TechnicalDocScoring 归一化",
                        "fund 来自 PE 相对行业",
                        "rotation 含板块 5 日强度与行业 5 日收益"
                ),
                55,
                "含 5 日板块/行业因子，对 3～5 日有一定相关性，但基本面项拖慢响应",
                List.of(
                        "行业修正×0.5，绝对分受行业偏差影响",
                        "外部情绪为库内代理，非实时新闻"
                ),
                List.of(
                        "短周期可降权 fundamental，或仅用 tech+rotation 子分排序做实验",
                        "验证 MULTI_DIM 在 T+5 IC 是否优于 CLASSIC medium"
                )
        );
    }

    private static FormulaAudit auditGeneric(String st, String fv) {
        return new FormulaAudit(
                st,
                fv,
                HORIZON_MEDIUM_TREND,
                "未注册策略类型，无法自动审计公式",
                "unknown",
                List.of(),
                0,
                "请补充 StrategyFormulaRegistry 映射后再做短周期结论",
                List.of("策略类型未识别"),
                List.of("在回测页选择已知 strategyType 并查看 factor_snapshot")
        );
    }
}
