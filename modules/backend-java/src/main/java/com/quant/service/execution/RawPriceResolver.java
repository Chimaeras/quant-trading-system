package com.quant.service.execution;

import com.quant.entity.MarketPriceRaw;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 统一 raw-only 成交价解析（V2-MP-014）。
 *
 * <p>研究/回测/账户的成交定价只读 {@code market_price_raw}；缺 raw 一律返回
 * {@link RawPriceResult#reject(String)}，禁止用 qfq 前复权价"救活"成交样本
 * （前复权价含除权调整，不能作为真实成交价）。
 *
 * <p>展示型用途（技术指标、净值标记）仍可走 qfq，但那不属于本类职责。
 *
 * <p>纯静态工具，无 Spring 依赖，便于在 ScoringBacktest/PortfolioBacktest/SimAccount/LabelFactory
 * 各处复用同一套判定与拒绝理由。
 */
public final class RawPriceResolver {

    private RawPriceResolver() {}

    public static final String REJECT_NO_QUOTE_RAW_MISSING = "NO_QUOTE_RAW_MISSING";

    /** 成交价解析结果：要么 raw 成交价，要么明确的拒绝理由。 */
    public record RawPriceResult(
            MarketPriceRaw raw,
            BigDecimal openPrice,
            BigDecimal closePrice,
            LocalDate tradeDate,
            boolean ok,
            String rejectReason
    ) {
        public static RawPriceResult ok(MarketPriceRaw raw) {
            return new RawPriceResult(raw, raw.getOpenPrice(), raw.getClosePrice(), raw.getTradeDate(), true, null);
        }

        public static RawPriceResult reject(String reason) {
            return new RawPriceResult(null, null, null, null, false, reason);
        }

        public boolean hasOpenPrice() {
            return ok && openPrice != null && openPrice.signum() > 0;
        }

        public boolean hasClosePrice() {
            return ok && closePrice != null && closePrice.signum() > 0;
        }

        /** 零成交量视为停牌代理（研究口径与 ScoringBacktest 一致）。 */
        public boolean isZeroVolume() {
            return ok && raw != null && raw.getVolume() != null && raw.getVolume() <= 0;
        }
    }

    /**
     * 解析某股票某交易日 raw 成交价。
     *
     * @param fetch 调用方提供的 raw 拉取函数（按 stock/exchange/date 查 market_price_raw）
     */
    public static RawPriceResult resolve(
            String stockCode,
            String exchangeType,
            LocalDate date,
            RawBarFetcher fetch
    ) {
        if (stockCode == null || exchangeType == null || date == null) {
            return RawPriceResult.reject(REJECT_NO_QUOTE_RAW_MISSING);
        }
        MarketPriceRaw raw = fetch != null ? fetch.find(stockCode, exchangeType, date) : null;
        if (raw == null || (raw.getOpenPrice() == null && raw.getClosePrice() == null)) {
            return RawPriceResult.reject(REJECT_NO_QUOTE_RAW_MISSING);
        }
        return RawPriceResult.ok(raw);
    }

    @FunctionalInterface
    public interface RawBarFetcher {
        MarketPriceRaw find(String stockCode, String exchangeType, LocalDate tradeDate);
    }
}
