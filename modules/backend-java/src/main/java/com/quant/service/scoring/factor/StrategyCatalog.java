package com.quant.service.scoring.factor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L3 层：5 个策略的声明式配置目录。
 *
 * <p><b>关于权重的诚实说明</b>：下表权重是按各策略<b>原文档化的维度结构</b>映射到统一因子后的
 * 初值，<b>未经样本外校准</b>。原因：本库当前有效截面日只有 4 天、0 个校准器、0 次候选核销，
 * 任何"最优权重"都无法被数据支持（见核验报告 §0.3）。
 * 统一引擎的<b>目的不是</b>让分数更准，而是让分数第一次变得<b>可校准、可归因、可复现</b>——
 * 权重集中在这里，改一处即全局生效，且每次改动都能用同一套口径回测验证。
 *
 * <h2>2026-10-15 关于「因子符号是否反了」的结论：<b>没有反，权重保持原样</b></h2>
 *
 * <p>期间曾根据 Rank IC 证据把这 6 个因子（TREND_MA / VOL_RATIO / RSI_14 / MOM_20 /
 * TREND_BIAS / MOM_60）的符号由正改负，<b>随后被组合层回测推翻并已回滚</b>。
 * 记录在此以免后人重复这条弯路：
 *
 * <ol>
 *   <li><b>Rank IC 确实说"负"</b>：逐因子 T+5 IC ≈ −0.02~−0.07，且在每个子段、每套策略一致。
 *       翻符号后逐日 IC 由 −0.035 变为 +0.038（NW-t = +2.06）。</li>
 *   <li><b>但十分组"平均收益"说"正"</b>，而且两种 IC 口径都自相矛盾：
 *       <pre>
 *   方案        逐日Spearman均值   全观测合并Spearman   D10−D1    D10−D1>0 段数
 *   原权重(保留)      +0.0377            +0.0182        +0.122%      2/4
 *   翻符号(已回滚)    −0.0463            −0.0391        −0.396%      1/4</pre>
 *       翻符号后<b>两种 IC 口径都变成负的</b>，且高处十分位（D10）平均收益由 +0.037%
 *       掉到 <b>−0.328%</b>（超出全样本均值 0.34% 达 −0.67pp）。</li>
 *   <li><b>分歧的根源</b>：Spearman IC 度量的是<b>整体秩相关</b>，对"极值端"不敏感；
 *       而 TopN 组合只买<b>最极端的那一端</b>。原权重的形状是<b>正向驼峰</b>——
 *       上半部（D6~D10）全部好于下半部（D1~D5），所以 IC 为正；但极值端 D10 略低于 D6，
 *       所以 Top20 组合并不赚钱。翻符号后形状整体倒过来，D10 变成最差的一档，
 *       于是 IC 与极值端<b>同时</b>变差。</li>
 *   <li><b>结论</b>：原权重在"排序"和"极值端"两个口径上都不差于翻符号版，
 *       故<b>不做符号翻转</b>。真正的问题不在符号，而在<b>信号太弱</b>：
 *       全样本十分组极差仅约 0.5pp/5日，且 Top20 相对合格池等权<b>没有正超额</b>
 *       （见阶段6 报告）。</li>
 * </ol>
 *
 * <p><b>教训（方法论）</b>：只凭 Rank IC 就改权重是危险的。IC 显著为正
 * <b>不蕴含</b>"TopN 组合能赚钱"，因为二者对分布的敏感区域不同。
 * 任何权重改动都必须同时看 <b>①秩相关 ②十分组平均收益 ③组合层扣成本净值</b>，
 * 三者一致才可采纳。本项目 ②③ 由 `decile_returns.py` / `portfolio_backtest.py` 给出。
 *
 * <p>各策略权重的来源映射：
 * <table border="1">
 *   <tr><th>策略</th><th>原实现结构</th><th>本目录映射</th></tr>
 *   <tr><td>CLASSIC_SELECTOR</td>
 *       <td>{@code ClassicFactorCalculator} 0.35·trend + 0.20·momentum + 0.20·volume − 0.25·risk</td>
 *       <td>TREND_MA/TREND_MACD + MOM_20 + VOL_RATIO/VOL_PRICE + VOLATILITY_20/MAXDD_60</td></tr>
 *   <tr><td>TREND</td>
 *       <td>{@code StockTrendScoringService} 12 子项（注释称满分 100，实际可达 102）</td>
 *       <td>TREND_MA/TREND_BIAS/TREND_MACD + RSI_14 + VOL_RATIO/VOL_TURNOVER</td></tr>
 *   <tr><td>OVERSOLD_REBOUND</td>
 *       <td>{@code OversoldReboundScoringService} 趋势40 + 量能30 + 超跌30 + 确认15</td>
 *       <td>MOM_60/TREND_MA + VOL_RATIO + <b>REV_5</b> + VOL_PRICE/KDJ_J</td></tr>
 *   <tr><td>COMPREHENSIVE</td>
 *       <td>技术 30 + 基本面 40 + 情绪 30（30/40/30 是归一化分母而非权重）</td>
 *       <td>技术因子 + VAL_EP/VAL_BP/QUAL_ROE（<b>当前全缺</b>）+ 量价/动量</td></tr>
 *   <tr><td>MULTI_DIM</td>
 *       <td>技术/基本面/政策/轮动/行业 5 维（预归一化权重和 1.10）</td>
 *       <td>技术因子为主 + 估值（当前全缺）；<b>移除</b>行业加分（改为回归中性化）</td></tr>
 * </table>
 */
public final class StrategyCatalog {

    /** 与 {@code UnifiedScoringStrategy.TREND} 对应的因子版本号。 */
    public static final String FV_CLASSIC = "unified_factor_classic_v1";
    public static final String FV_TREND = "unified_factor_trend_v1";
    public static final String FV_OVERSOLD = "unified_factor_oversold_v1";
    public static final String FV_COMPREHENSIVE = "unified_factor_comprehensive_v1";
    public static final String FV_MULTI_DIM = "unified_factor_multi_dim_v1";

    private StrategyCatalog() {}

    private static final Map<String, StrategyDefinition> BY_LEGACY_TYPE = new LinkedHashMap<>();

    static {
        for (StrategyDefinition d : all()) {
            BY_LEGACY_TYPE.put(d.legacyStrategyType(), d);
        }
    }

    /** 全部策略定义，顺序固定。 */
    public static java.util.List<StrategyDefinition> all() {
        return java.util.List.of(
                classic(),
                trend(),
                oversold(),
                comprehensive(),
                multiDim());
    }

    /** 按既有 {@code strategy_type} 取定义；未知返回 null。 */
    public static StrategyDefinition byLegacyStrategyType(String legacyType) {
        if (legacyType == null) {
            return null;
        }
        StrategyDefinition d = BY_LEGACY_TYPE.get(legacyType.trim().toUpperCase());
        if (d != null) {
            return d;
        }
        // 别名，与 UnifiedScoringStrategy.parse 保持一致
        return switch (legacyType.trim().toUpperCase()) {
            case "CLASSIC", "SELECTOR" -> classic();
            case "OVERSOLD", "OVERSOLD-REBOUND" -> oversold();
            case "MULTI-DIM", "MULTIDIM" -> multiDim();
            default -> null;
        };
    }

    /**
     * 经典多因子：对应 {@code ClassicFactorCalculator} 的 0.35 / 0.20 / 0.20 / 0.25。
     * risk 维度（0.25）按原实现对 vol20 与 mdd60 均等拆分。
     */
    public static StrategyDefinition classic() {
        return StrategyDefinition.builder("CLASSIC_SELECTOR", "经典多因子", FV_CLASSIC)
                .weight(FactorId.TREND_MA, 0.20)      // trend 维度 0.35，MACD 占剩余
                .weight(FactorId.TREND_MACD, 0.15)
                .weight(FactorId.MOM_20, 0.20)        // momentum 维度
                .weight(FactorId.VOL_RATIO, 0.12)     // volume 维度 0.20
                .weight(FactorId.VOL_PRICE, 0.08)
                .weight(FactorId.VOLATILITY_20, -0.125) // risk 维度 0.25，负向
                .weight(FactorId.MAXDD_60, -0.125)
                .minBars(70)                          // 原 CFC.MIN_BARS
                .minAvgAmount(30000)
                .minListingDays(60)
                .minAvailableWeightRatio(0.5)
                .legacyStrategyType("CLASSIC_SELECTOR").legacyUiMode("classic")
                .build();
    }

    /**
     * 趋势打分：对应 {@code StockTrendScoringService} 的 12 子项。
     * 原实现 12 子项注释称满分 100 但实际可达 102——本目录用归一化权重消除了这种不一致。
     */
    public static StrategyDefinition trend() {
        return StrategyDefinition.builder("TREND", "趋势打分", FV_TREND)
                .weight(FactorId.TREND_MA, 0.22)
                .weight(FactorId.TREND_BIAS, 0.12)
                .weight(FactorId.TREND_MACD, 0.16)
                .weight(FactorId.RSI_14, 0.12)
                .weight(FactorId.MOM_20, 0.10)
                .weight(FactorId.VOL_RATIO, 0.14)
                .weight(FactorId.VOL_TURNOVER, 0.08)
                .weight(FactorId.VOL_PRICE, 0.06)
                // 原实现的 scoreSupport（均线支撑）用 MA_SUPPORT_TOLERANCE 判「价格贴近均线」，
                // 统一层用 TREND_BIAS 的绝对值表达（贴近 0 即为支撑），不再单列因子。
                .minBars(60)                          // 原 STS.MIN_BARS_TECHNICAL
                .minAvgAmount(30000)
                .minListingDays(60)
                .minAvailableWeightRatio(0.5)
                .legacyStrategyType("TREND").legacyUiMode("trend")
                .build();
    }

    /**
     * 超跌反弹：对应 {@code OversoldReboundScoringService} 的 40/30/30/15。
     *
     * <p>关键的语义修正：原实现的"超跌程度"用绝对阈值判断 RSI&lt;30 / 20 日跌幅等，
     * 统一层改用 <b>{@link FactorId#REV_5} 的横截面分位</b>——即"相对同批股票谁跌得更狠"，
     * 而不是"是否跌破某个绝对线"。这既消除了多套 RSI 标准并存的问题，
     * 也符合 A 股短期反转效应是<b>横截面</b>现象这一实证结论。
     */
    public static StrategyDefinition oversold() {
        return StrategyDefinition.builder("OVERSOLD_REBOUND", "超跌反弹", FV_OVERSOLD)
                .weight(FactorId.MOM_60, 0.12)        // 趋势确认（中期未破位）
                .weight(FactorId.TREND_MA, 0.10)
                .weight(FactorId.REV_5, 0.22)         // 超跌程度（横截面反转）
                .weight(FactorId.VOL_RATIO, 0.14)     // 量能验证
                .weight(FactorId.VOL_PRICE, 0.10)     // 缩量→放量确认
                .weight(FactorId.KDJ_J, 0.08)         // 超卖确认
                .weight(FactorId.RSI_14, 0.05)        // 超卖确认
                .weight(FactorId.LIQUIDITY_20, 0.05)  // 流动性
                .weight(FactorId.VOLATILITY_20, -0.08) // 风险惩罚
                .weight(FactorId.MAXDD_60, -0.06)
                .minBars(260)                          // 原 ORS.MIN_BARS_OVERSOLD
                .minAvgAmount(30000)
                .minListingDays(60)
                // 原实现的"确认分"是必需项（no-confirm 时加权分封顶 70），故 VOL_PRICE 为必需
                .require(FactorId.VOL_PRICE)
                .minAvailableWeightRatio(0.5)
                .legacyStrategyType("OVERSOLD_REBOUND").legacyUiMode("oversold")
                .build();
    }

    /**
     * 综合三维度：对应 {@code ComprehensiveScoringService} 的技术30 / 基本面40 / 情绪30。
     *
     * <p><b>重要披露</b>：基本面因子（VAL_EP/VAL_BP/QUAL_ROE/LIQ_CURRENT/QUAL_LEV）在本库
     * <b>当前全部不可用</b>（PE/PB 填充率 0.0%、financial_snapshot 0 行）。
     * 因此本策略在数据补齐前必然降级为"技术面 + 量价"打分，
     * 输出会带 {@code missing_weight_share} 与 {@code data_quality} 标注，
     * <b>不再</b>用中性常量冒充基本面分（原实现给 PE 缺失打 60 分常数）。
     */
    public static StrategyDefinition comprehensive() {
        return StrategyDefinition.builder("COMPREHENSIVE", "综合三维度", FV_COMPREHENSIVE)
                // 技术面 30
                .weight(FactorId.TREND_MA, 0.10)
                .weight(FactorId.TREND_MACD, 0.08)
                .weight(FactorId.KDJ_J, 0.06)
                .weight(FactorId.RSI_14, 0.06)
                // 基本面 40（当前全缺，可用时自动按权重重新归一化生效）
                .weight(FactorId.VAL_EP, 0.16)
                .weight(FactorId.VAL_BP, 0.10)
                .weight(FactorId.QUAL_ROE, 0.09)
                .weight(FactorId.QUAL_LEV, -0.05)
                // 情绪面 30 —— 原实现三个子项输入全是价量（循环论证），
                // 统一层改用「量价 + 换手 + 乖离」，语义诚实：这就是技术面推导，不是外部舆情。
                .weight(FactorId.VOL_RATIO, 0.12)
                .weight(FactorId.VOL_TURNOVER, 0.08)
                .weight(FactorId.TREND_BIAS, 0.10)
                .minBars(60)                          // 原 CSS.MIN_BARS_COMPREHENSIVE
                .minAvgAmount(30000)
                .minListingDays(60)
                .minAvailableWeightRatio(0.4)         // 基本面全缺时仍可打分（技术+情绪权重 0.60）
                .legacyStrategyType("COMPREHENSIVE").legacyUiMode("comprehensive")
                .build();
    }

    /**
     * 多维度 v1：对应 {@code MultiDimScoringService} 的 5 维度。
     *
     * <p>两处语义修正：
     * <ol>
     *   <li><b>移除行业加分</b>。原实现把 {@code getIndustryAdjustment(industry) * 0.5} 直接加到
     *       复合分上，且实测因 {@code industry} 全空而恒为 0；统一层改为回归中性化，
     *       行业影响从因子值里被"回归掉"而非"加回来"。</li>
     *   <li><b>移除政策权重</b>。原实现 {@code scorePolicy} 恒为 0.0 且根本不参与复合计算
     *       （{@code MultiDimScoringService} 的加权求和里没有它），却占了权重表一项。</li>
     * </ol>
     */
    public static StrategyDefinition multiDim() {
        return StrategyDefinition.builder("MULTI_DIM", "多维度v1", FV_MULTI_DIM)
                // 技术面（原 tech 维度）
                .weight(FactorId.TREND_MA, 0.14)
                .weight(FactorId.TREND_MACD, 0.10)
                .weight(FactorId.RSI_14, 0.09)
                .weight(FactorId.KDJ_J, 0.07)
                .weight(FactorId.VOL_RATIO, 0.10)
                // 基本面 / 估值（原 fundamental + valuation + profitability + financial_health，当前全缺）
                .weight(FactorId.VAL_EP, 0.14)
                .weight(FactorId.VAL_BP, 0.09)
                .weight(FactorId.QUAL_ROE, 0.08)
                .weight(FactorId.LIQ_CURRENT, 0.05)
                .weight(FactorId.QUAL_LEV, -0.04)
                // 原 rotation 维度：本库无板块资金流数据，用流动性 + 量比近似「关注度」
                .weight(FactorId.LIQUIDITY_20, 0.05)
                .weight(FactorId.VOL_PRICE, 0.05)
                .minBars(260)                         // 原为 30，但 30 根会让多数技术因子不可用（见下）
                .minAvgAmount(30000)
                .minListingDays(60)
                .minAvailableWeightRatio(0.4)
                .legacyStrategyType("MULTI_DIM").legacyUiMode("multi_dim")
                .build();
    }
}
