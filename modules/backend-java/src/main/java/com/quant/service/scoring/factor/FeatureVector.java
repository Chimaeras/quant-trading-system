package com.quant.service.scoring.factor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L1 层：单只股票的<b>原始特征向量</b>——纯个股口径，不含任何横截面信息。
 *
 * <p>设计要点（对应《打分模块修复与统一重估方案》§2.2）：
 * <ul>
 *   <li><b>每只股票只算一次</b>，5 个策略共用同一份取值。当前实现的问题是同一个「趋势」
 *       在趋势策略与超跌策略里各算一遍、阈值还不同。</li>
 *   <li><b>缺失即 {@code null}</b>，绝不填 0 或行业中位数。原因：写本类时实测
 *       {@code market_data.pe_ratio}/{@code pb_ratio} 填充率 0.0%（256 万行全 NULL）、
 *       {@code stock_financial_snapshot} 0 行、{@code stock_info.industry} 填充率 0.0%。
 *       若把缺失填成常数，则「基本面维度」会拿常数打分却对外宣称多维度——
 *       实测 MULTI_DIM 的 {@code momentum_score} 恒为 60.0 就是这么来的。
 *       <p><b>2026-10 更新</b>：财务快照与行业已开始补数据，
 *       故上述填充率**不再是常量**，引用时必须以运行时统计为准，不要照抄旧数字。
 *       其中 {@code pe_ratio}/{@code pb_ratio} 至今仍为 100% NULL，未变。</li>
 *   <li>字段全部为原始量纲（收益率用 %、成交额用万元、比率用小数），
 *       标准化一律交给 L2 层。</li>
 * </ul>
 *
 * <p>本类不做任何过滤判断——可交易性与最小 bar 数由调用方在更外层处理。
 */
public final class FeatureVector {

    /** 股票标识。 */
    private final String stockCode;
    private final String exchangeType;
    private final String stockName;
    private final java.time.LocalDate tradeDate;
    /** 参与计算的 K 线条数（已按交易日去重）。 */
    private final int barCount;

    // ── 动量 / 反转（%）──────────────────────────────────────────────
    private Double ret1;
    private Double ret5;
    private Double ret20;
    private Double ret60;
    private Double ret120;

    // ── 趋势 ────────────────────────────────────────────────────────
    private Double ma5;
    private Double ma10;
    private Double ma20;
    private Double ma60;
    private Double ma120;
    /** 均线多头排列强度：4 条均线两两比较的标准化得分，见 {@link FactorCalculator}。 */
    private Double trendMaAlignment;
    /** 5 日乖离率（%）：(close - ma5) / ma5 * 100。 */
    private Double bias5;

    // ── 技术指标 ────────────────────────────────────────────────────
    private Double rsi14;
    /** MACD 柱 / 收盘价 × 100（去掉量纲，便于横截面比较）。 */
    private Double macdHistPct;
    private Double kdjJ;

    // ── 量能 ────────────────────────────────────────────────────────
    /** 量比：近 5 日均量 / 近 30 日均量。 */
    private Double volumeRatio;
    private Double turnoverRate;
    /** 20 日日均成交额（万元），与 {@code ScoringUtils.avgAmount20} 同口径。 */
    private Double avgAmount20;
    /** 量价配合方向：1 放量上涨 / -1 放量下跌 / 0.5 缩量上涨 / -0.5 缩量下跌 / 0 中性。 */
    private Double volPriceConfirm;

    // ── 风险 ────────────────────────────────────────────────────────
    /** 20 日波动率（%）：收益率序列标准差 × 100。 */
    private Double volatility20;
    /** 60 日最大回撤（%）。 */
    private Double maxDrawdown60;

    // ── 估值 / 财务（本库当前全缺，保留以承接未来数据）──────────────────
    private Double peRatio;
    private Double pbRatio;
    private Double roe;
    private Double debtRatio;
    private Double currentRatio;

    // ── 市值（中性化用）──────────────────────────────────────────────
    private Double logMarketCap;
    private String logMarketCapSource;
    private String logMarketCapReliability;
    private String industry;

    private FeatureVector(String stockCode, String exchangeType, String stockName,
                          java.time.LocalDate tradeDate, int barCount) {
        this.stockCode = stockCode;
        this.exchangeType = exchangeType;
        this.stockName = stockName;
        this.tradeDate = tradeDate;
        this.barCount = barCount;
    }

    public static FeatureVector of(String stockCode, String exchangeType, String stockName,
                                   java.time.LocalDate tradeDate, int barCount) {
        return new FeatureVector(stockCode, exchangeType, stockName, tradeDate, barCount);
    }

    public String stockCode() { return stockCode; }
    public String exchangeType() { return exchangeType; }
    public String stockName() { return stockName; }
    public java.time.LocalDate tradeDate() { return tradeDate; }
    public int barCount() { return barCount; }

    public Double ret1() { return ret1; }
    public Double ret5() { return ret5; }
    public Double ret20() { return ret20; }
    public Double ret60() { return ret60; }
    public Double ret120() { return ret120; }
    public Double ma5() { return ma5; }
    public Double ma10() { return ma10; }
    public Double ma20() { return ma20; }
    public Double ma60() { return ma60; }
    public Double ma120() { return ma120; }
    public Double trendMaAlignment() { return trendMaAlignment; }
    public Double bias5() { return bias5; }
    public Double rsi14() { return rsi14; }
    public Double macdHistPct() { return macdHistPct; }
    public Double kdjJ() { return kdjJ; }
    public Double volumeRatio() { return volumeRatio; }
    public Double turnoverRate() { return turnoverRate; }
    public Double avgAmount20() { return avgAmount20; }
    public Double volPriceConfirm() { return volPriceConfirm; }
    public Double volatility20() { return volatility20; }
    public Double maxDrawdown60() { return maxDrawdown60; }
    public Double peRatio() { return peRatio; }
    public Double pbRatio() { return pbRatio; }
    public Double roe() { return roe; }
    public Double debtRatio() { return debtRatio; }
    public Double currentRatio() { return currentRatio; }
    public Double logMarketCap() { return logMarketCap; }
    public String logMarketCapSource() { return logMarketCapSource; }
    public String logMarketCapReliability() { return logMarketCapReliability; }
    public String industry() { return industry; }

    // ── setter（仅包内 FactorCalculator 使用）─────────────────────────

    void setRet1(Double v) { this.ret1 = v; }
    void setRet5(Double v) { this.ret5 = v; }
    void setRet20(Double v) { this.ret20 = v; }
    void setRet60(Double v) { this.ret60 = v; }
    void setRet120(Double v) { this.ret120 = v; }
    void setMa5(Double v) { this.ma5 = v; }
    void setMa10(Double v) { this.ma10 = v; }
    void setMa20(Double v) { this.ma20 = v; }
    void setMa60(Double v) { this.ma60 = v; }
    void setMa120(Double v) { this.ma120 = v; }
    void setTrendMaAlignment(Double v) { this.trendMaAlignment = v; }
    void setBias5(Double v) { this.bias5 = v; }
    void setRsi14(Double v) { this.rsi14 = v; }
    void setMacdHistPct(Double v) { this.macdHistPct = v; }
    void setKdjJ(Double v) { this.kdjJ = v; }
    void setVolumeRatio(Double v) { this.volumeRatio = v; }
    void setTurnoverRate(Double v) { this.turnoverRate = v; }
    void setAvgAmount20(Double v) { this.avgAmount20 = v; }
    void setVolPriceConfirm(Double v) { this.volPriceConfirm = v; }
    void setVolatility20(Double v) { this.volatility20 = v; }
    void setMaxDrawdown60(Double v) { this.maxDrawdown60 = v; }
    void setPeRatio(Double v) { this.peRatio = v; }
    void setPbRatio(Double v) { this.pbRatio = v; }
    void setRoe(Double v) { this.roe = v; }
    void setDebtRatio(Double v) { this.debtRatio = v; }
    void setCurrentRatio(Double v) { this.currentRatio = v; }
    public void setLogMarketCap(Double v, String source, String reliability) {
        this.logMarketCap = v;
        this.logMarketCapSource = source;
        this.logMarketCapReliability = reliability;
    }
    public void setIndustry(String v) { this.industry = v; }

    /**
     * 原始特征快照（诊断/落库用）。<b>null 值照原样保留</b>，
     * 使「未计算」在快照里可辨识。
     */
    public Map<String, Object> toRawMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stock_code", stockCode);
        m.put("exchange_type", exchangeType);
        m.put("bar_count", barCount);
        m.put("ret1", ret1);
        m.put("ret5", ret5);
        m.put("ret20", ret20);
        m.put("ret60", ret60);
        m.put("ret120", ret120);
        m.put("bias5", bias5);
        m.put("rsi14", rsi14);
        m.put("macd_hist_pct", macdHistPct);
        m.put("kdj_j", kdjJ);
        m.put("volume_ratio", volumeRatio);
        m.put("turnover_rate", turnoverRate);
        m.put("avg_amount20", avgAmount20);
        m.put("vol_price_confirm", volPriceConfirm);
        m.put("volatility20", volatility20);
        m.put("max_drawdown60", maxDrawdown60);
        m.put("pe_ratio", peRatio);
        m.put("pb_ratio", pbRatio);
        m.put("roe", roe);
        m.put("debt_ratio", debtRatio);
        m.put("current_ratio", currentRatio);
        m.put("log_market_cap", logMarketCap);
        m.put("log_market_cap_source", logMarketCapSource);
        m.put("log_market_cap_reliability", logMarketCapReliability);
        m.put("industry", industry);
        return m;
    }
}
