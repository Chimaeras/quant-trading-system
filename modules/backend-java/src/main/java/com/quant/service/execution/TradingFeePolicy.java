package com.quant.service.execution;

import com.quant.research.ResearchConvention;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 统一 A 股交易费用政策（V2-MP-014）。
 *
 * <p>此前佣金/印花税/滑点/最低佣金/整手在各服务中分散复制且口径不一致
 * （如 SimAccount 无最低佣金、ScoringBacktest 无整手、PortfolioBacktest 独有 5 元最低佣金）。
 * 本类是唯一费用口径来源：
 * <ul>
 *   <li>佣金率 0.0003、最低佣金 5 元/笔（买入卖出各计一笔，取 max）。</li>
 *   <li>印花税 0.0005（仅卖出）。</li>
 *   <li>滑点 0.0002（买卖各一侧，乘在价格上）。</li>
 *   <li>整手 100 股。</li>
 * </ul>
 * 费率常量与 {@link ResearchConvention} 保持一致；金额计算统一用 BigDecimal，避免 double 误差。
 */
public final class TradingFeePolicy {

    private TradingFeePolicy() {}

    public static final String VERSION = "trading_fee_v1";

    /** 佣金率（单边）。 */
    public static final BigDecimal COMMISSION_RATE = BigDecimal.valueOf(ResearchConvention.DEFAULT_COMMISSION_RATE);
    /** 印花税率（仅卖出）。 */
    public static final BigDecimal SELL_TAX_RATE = BigDecimal.valueOf(ResearchConvention.DEFAULT_SELL_TAX_RATE);
    /** 滑点率（单边）。 */
    public static final BigDecimal SLIPPAGE_RATE = BigDecimal.valueOf(ResearchConvention.DEFAULT_SLIPPAGE_RATE);
    /** 每笔最低佣金（元）。 */
    public static final BigDecimal MIN_COMMISSION = BigDecimal.valueOf(5.0);
    /** A 股整手股数。 */
    public static final int LOT_SIZE = 100;

    /**
     * 一笔成交的佣金与税费分解。
     *
     * @param gross 成交金额（=成交价×股数，未含滑点调整）
     * @param sell  是否卖出（印花税仅卖出收取）
     */
    public static FeeBreakdown breakdown(BigDecimal gross, boolean sell) {
        if (gross == null || gross.signum() <= 0) {
            return FeeBreakdown.zero();
        }
        BigDecimal rawCommission = gross.multiply(COMMISSION_RATE);
        BigDecimal commission = rawCommission.max(MIN_COMMISSION).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stampDuty = sell
                ? gross.multiply(SELL_TAX_RATE).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new FeeBreakdown(commission, stampDuty, VERSION, rawCommission.compareTo(MIN_COMMISSION) < 0);
    }

    /** 买入后成交价（含滑点）：price × (1+滑点)。 */
    public static BigDecimal buyExecPrice(BigDecimal rawPrice) {
        return rawPrice.multiply(BigDecimal.ONE.add(SLIPPAGE_RATE)).setScale(4, RoundingMode.HALF_UP);
    }

    /** 卖出后成交价（含滑点）：price × (1-滑点)。 */
    public static BigDecimal sellExecPrice(BigDecimal rawPrice) {
        return rawPrice.multiply(BigDecimal.ONE.subtract(SLIPPAGE_RATE)).setScale(4, RoundingMode.HALF_UP);
    }

    /** 把 double 原始价转为 4 位小数的 BigDecimal 成交价。 */
    public static BigDecimal priceOf(double raw) {
        return BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_UP);
    }

    public record FeeBreakdown(BigDecimal commission, BigDecimal stampDuty, String version, boolean minApplied) {
        public static FeeBreakdown zero() {
            return new FeeBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, VERSION, false);
        }
    }
}
