package com.quant.service;

import com.quant.config.AuthProperties;
import com.quant.entity.*;
import com.quant.exception.BusinessException;
import com.quant.repository.*;
import com.quant.research.ResearchConvention;
import com.quant.security.AuthContext;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 模拟账户（MP-026）：与研究回测共用成本口径；省略 price 时须指定 tradeDate，优先 raw 价并标注 price_source。
 */
@Service
@RequiredArgsConstructor
public class SimAccountService {

    private static final BigDecimal COMMISSION_RATE =
            BigDecimal.valueOf(ResearchConvention.DEFAULT_COMMISSION_RATE);
    private static final BigDecimal STAMP_DUTY_RATE =
            BigDecimal.valueOf(ResearchConvention.DEFAULT_SELL_TAX_RATE);
    private static final BigDecimal SLIPPAGE_RATE =
            BigDecimal.valueOf(ResearchConvention.DEFAULT_SLIPPAGE_RATE);
    private static final int LOT_SIZE = 100;

    private final AuthProperties authProperties;
    private final AuthService authService;
    private final UserAccountRepository userAccountRepository;
    private final PositionInfoRepository positionInfoRepository;
    private final TradingLogRepository tradingLogRepository;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final StockInfoRepository stockInfoRepository;

    public Map<String, Object> getAccountInfo(Long userId) {
        UserAccount account = resolveAccount(userId);
        refreshAccountMetrics(account);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", account.getId());
        data.put("totalAsset", account.getTotalAsset());
        data.put("cash", account.getAvailableCash());
        data.put("positionValue", account.getPositionValue());
        data.put("todayPnl", account.getTodayProfit());
        data.put("totalProfit", account.getTotalProfit());
        data.put("positionCount", positionInfoRepository.findByAccountIdOrderByStockCodeAsc(account.getId()).size());
        return data;
    }

    public List<Map<String, Object>> listPositions(Long userId) {
        UserAccount account = resolveAccount(userId);
        List<PositionInfo> positions = positionInfoRepository.findByAccountIdOrderByStockCodeAsc(account.getId());
        List<Map<String, Object>> list = new ArrayList<>();
        for (PositionInfo p : positions) {
            refreshSellableVolume(p, account.getId());
            refreshPositionPrice(p);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", p.getStockCode());
            m.put("name", p.getStockName());
            m.put("exchangeType", p.getExchangeType());
            m.put("volume", p.getVolume());
            m.put("availableVolume", p.getAvailableVolume());
            m.put("avgPrice", p.getAvgCost());
            m.put("currentPrice", p.getCurrentPrice());
            m.put("marketValue", p.getMarketValue());
            m.put("profitLoss", p.getProfitLoss());
            m.put("profitRate", p.getProfitRate());
            list.add(m);
        }
        return list;
    }

    /** 账户订单/成交历史（最近 50 条，按时间倒序）。 */
    public List<Map<String, Object>> orderHistory(Long userId) {
        UserAccount account = resolveAccount(userId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TradingLog t : tradingLogRepository.findTop50ByAccountIdOrderByTradeTimeDesc(account.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", t.getOrderId());
            m.put("stockCode", t.getStockCode());
            m.put("stockName", t.getStockName());
            m.put("exchangeType", t.getExchangeType());
            m.put("action", t.getTradeType() != null && t.getTradeType() == 1 ? "buy" : "sell");
            m.put("price", t.getTradePrice());
            m.put("volume", t.getTradeVolume());
            m.put("amount", t.getTradeAmount());
            m.put("commission", t.getCommission());
            m.put("stampDuty", t.getStampDuty());
            m.put("tradeTime", t.getTradeTime());
            list.add(m);
        }
        return list;
    }

    @Transactional
    public Map<String, Object> placeOrder(Long userId, Map<String, Object> request) {
        UserAccount account = resolveAccount(userId);
        String stockCode = String.valueOf(request.getOrDefault("symbol", request.getOrDefault("stockCode", ""))).trim();
        String exchange = String.valueOf(request.getOrDefault("exchangeType", "SZSE")).trim();
        String action = String.valueOf(request.getOrDefault("action", "")).trim().toLowerCase();
        int volume = parseInt(request.get("volume"), 0);

        if (stockCode.isBlank()) {
            return reject("INVALID_SYMBOL", "股票代码不能为空");
        }
        if (!"buy".equals(action) && !"sell".equals(action)) {
            return reject("INVALID_ACTION", "action 仅支持 buy / sell");
        }
        if (volume <= 0 || volume % LOT_SIZE != 0) {
            return reject("INVALID_LOT", "数量须为 " + LOT_SIZE + " 的整数倍");
        }

        PriceResolve priceResolve;
        try {
            priceResolve = resolvePrice(stockCode, exchange, request.get("price"), request.get("tradeDate"));
        } catch (BusinessException e) {
            return reject("PRICE_RESOLVE_FAILED", e.getMessage());
        }
        BigDecimal price = priceResolve.price();
        String stockName = stockInfoRepository.findByStockCodeAndExchangeType(stockCode, exchange)
                .map(StockInfo::getStockName)
                .orElse(stockCode);

        try {
            ensureTradableAtPrice(stockCode, exchange, stockName, action, price, priceResolve.tradeDate());
        } catch (BusinessException e) {
            return reject("NOT_TRADABLE", e.getMessage());
        }

        BigDecimal slipAdj = "buy".equals(action)
                ? price.multiply(BigDecimal.ONE.add(SLIPPAGE_RATE))
                : price.multiply(BigDecimal.ONE.subtract(SLIPPAGE_RATE));
        BigDecimal execPrice = slipAdj.setScale(4, RoundingMode.HALF_UP);
        BigDecimal amount = execPrice.multiply(BigDecimal.valueOf(volume));
        BigDecimal commission = amount.multiply(COMMISSION_RATE).setScale(4, RoundingMode.HALF_UP);
        BigDecimal stamp = BigDecimal.ZERO;

        if ("buy".equals(action)) {
            BigDecimal cost = amount.add(commission);
            if (account.getAvailableCash().compareTo(cost) < 0) {
                return reject("INSUFFICIENT_CASH", "可用资金不足");
            }
            account.setAvailableCash(account.getAvailableCash().subtract(cost));
            upsertPositionBuy(account.getId(), stockCode, exchange, stockName, volume, execPrice);
        } else {
            PositionInfo pos = positionInfoRepository
                    .findByAccountIdAndStockCodeAndExchangeType(account.getId(), stockCode, exchange)
                    .orElse(null);
            if (pos == null) {
                return reject("NO_POSITION", "无可用持仓");
            }
            refreshSellableVolume(pos, account.getId());
            if (pos.getAvailableVolume() < volume) {
                return reject("T1_FROZEN", "可用持仓不足（A 股 T+1：当日买入不可卖出）");
            }
            stamp = amount.multiply(STAMP_DUTY_RATE).setScale(4, RoundingMode.HALF_UP);
            account.setAvailableCash(account.getAvailableCash().add(amount).subtract(commission).subtract(stamp));
            upsertPositionSell(pos, volume, account.getId());
        }

        userAccountRepository.save(account);
        refreshAccountMetrics(account);

        TradingLog log = new TradingLog();
        log.setOrderId("ORD" + System.currentTimeMillis());
        log.setStockCode(stockCode);
        log.setStockName(stockName);
        log.setExchangeType(exchange);
        log.setTradeType("buy".equals(action) ? 1 : 2);
        log.setTradePrice(execPrice);
        log.setTradeVolume(volume);
        log.setTradeAmount(amount);
        log.setCommission(commission);
        log.setStampDuty(stamp);
        log.setAccountId(account.getId());
        log.setUserId(userId);
        log.setOrderStatus(3);
        LocalDateTime tradeTs = priceResolve.tradeDate() != null
                ? priceResolve.tradeDate().atTime(15, 0)
                : LocalDateTime.now();
        log.setOrderTime(tradeTs);
        log.setTradeTime(tradeTs);
        tradingLogRepository.save(log);

        if ("buy".equals(action)) {
            positionInfoRepository.findByAccountIdAndStockCodeAndExchangeType(account.getId(), stockCode, exchange)
                    .ifPresent(p -> refreshSellableVolume(p, account.getId()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", log.getOrderId());
        result.put("status", "FILLED");
        result.put("action", action);
        result.put("price", execPrice);
        result.put("volume", volume);
        result.put("price_source", priceResolve.source());
        result.put("trade_date", priceResolve.tradeDate() != null ? priceResolve.tradeDate().toString() : null);
        result.put("commission", commission);
        result.put("stamp_duty", stamp);
        result.put("slippage_rate", SLIPPAGE_RATE);
        result.put("cost_version", ResearchConvention.CONVENTION_VERSION);
        result.put("reject_reason", null);
        return result;
    }

    private static Map<String, Object> reject(String code, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "REJECTED");
        result.put("reject_reason", reason);
        result.put("reject_code", code);
        return result;
    }

    public Long resolveUserId(Long explicitUserId) {
        if (authProperties.isEnabled()) {
            Long ctx = AuthContext.getUserId();
            if (ctx == null) {
                throw new BusinessException("未登录");
            }
            return ctx;
        }
        if (explicitUserId != null) {
            return explicitUserId;
        }
        return 1L;
    }

    private void ensureTradableAtPrice(
            String stockCode,
            String exchange,
            String stockName,
            String action,
            BigDecimal price,
            LocalDate tradeDate
    ) {
        Optional<MarketData> barOpt = tradeDate != null
                ? marketDataRepository.findByStockCodeAndExchangeTypeAndTradeDate(stockCode, exchange, tradeDate)
                : marketDataRepository.findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(stockCode, exchange);
        barOpt.ifPresent(bar -> {
            if (bar.getVolume() != null && bar.getVolume() <= 0) {
                throw new BusinessException("当日行情无成交量（疑似停牌），无法下单");
            }
            MarketData prevBar = marketDataRepository
                    .findFirstByStockCodeAndExchangeTypeAndTradeDateLessThanOrderByTradeDateDesc(
                            stockCode, exchange, bar.getTradeDate())
                    .orElse(null);
            double preClose = StrategyBacktestEngine.resolvePreClose(
                    bar, prevBar != null ? prevBar : new MarketData());
            if (preClose <= 0) {
                return;
            }
            MarketData execBar = new MarketData();
            execBar.setOpenPrice(price);
            execBar.setClosePrice(price);
            double limitPct = TradingLimitRules.resolveLimitPct(stockCode, exchange, stockName);
            if ("buy".equals(action)
                    && StrategyBacktestEngine.isLimitUpAtOpen(execBar, preClose, limitPct)) {
                throw new BusinessException("委托价处于涨停区间，无法买入");
            }
            if ("sell".equals(action)
                    && StrategyBacktestEngine.isLimitDownAtClose(execBar, preClose, limitPct)) {
                throw new BusinessException("委托价处于跌停区间，无法卖出");
            }
        });
    }

    private UserAccount resolveAccount(Long userId) {
        authService.ensureSimAccount(userId);
        return userAccountRepository.findFirstByUserIdAndStatus(userId, 1)
                .orElseThrow(() -> new BusinessException("模拟账户不存在"));
    }

    /**
     * 省略 price 时必须提供 tradeDate：优先 MarketPriceRaw.close，否则 MarketData.close，并标注 price_source。
     * 禁止静默使用「最新价」而不标注来源。
     */
    private PriceResolve resolvePrice(String stockCode, String exchange, Object priceObj, Object tradeDateObj) {
        if (priceObj != null && !String.valueOf(priceObj).isBlank()) {
            LocalDate td = parseDateOrNull(tradeDateObj);
            return new PriceResolve(new BigDecimal(String.valueOf(priceObj)), "CLIENT_SPECIFIED", td);
        }
        LocalDate tradeDate = parseDateOrNull(tradeDateObj);
        if (tradeDate == null) {
            throw new BusinessException("未指定 price 时必须提供 tradeDate（禁止静默使用未标注的最新价）");
        }
        Optional<MarketPriceRaw> raw = marketPriceRawRepository
                .findByStockCodeAndExchangeTypeAndTradeDate(stockCode, exchange, tradeDate);
        if (raw.isPresent() && raw.get().getClosePrice() != null) {
            return new PriceResolve(raw.get().getClosePrice(), "market_price_raw", tradeDate);
        }
        Optional<MarketData> qfq = marketDataRepository
                .findByStockCodeAndExchangeTypeAndTradeDate(stockCode, exchange, tradeDate);
        if (qfq.isPresent() && qfq.get().getClosePrice() != null) {
            return new PriceResolve(qfq.get().getClosePrice(), "qfq_legacy", tradeDate);
        }
        throw new BusinessException("tradeDate=" + tradeDate + " 无可用收盘价（raw/qfq 均缺失）");
    }

    private static LocalDate parseDateOrNull(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(raw).trim());
    }

    private void upsertPositionBuy(Long accountId, String code, String exchange, String name, int volume, BigDecimal price) {
        Optional<PositionInfo> opt = positionInfoRepository.findByAccountIdAndStockCodeAndExchangeType(accountId, code, exchange);
        if (opt.isPresent()) {
            PositionInfo p = opt.get();
            int newVol = p.getVolume() + volume;
            BigDecimal newCost = p.getAvgCost().multiply(BigDecimal.valueOf(p.getVolume()))
                    .add(price.multiply(BigDecimal.valueOf(volume)))
                    .divide(BigDecimal.valueOf(newVol), 4, RoundingMode.HALF_UP);
            p.setVolume(newVol);
            p.setAvgCost(newCost);
            p.setCurrentPrice(price);
            positionInfoRepository.save(p);
        } else {
            PositionInfo p = new PositionInfo();
            p.setAccountId(accountId);
            p.setStockCode(code);
            p.setStockName(name);
            p.setExchangeType(exchange);
            p.setVolume(volume);
            p.setAvailableVolume(0);
            p.setAvgCost(price);
            p.setCurrentPrice(price);
            p.setFirstBuyDate(LocalDate.now());
            positionInfoRepository.save(p);
        }
    }

    private void upsertPositionSell(PositionInfo p, int volume, Long accountId) {
        int remain = p.getVolume() - volume;
        if (remain <= 0) {
            positionInfoRepository.delete(p);
            return;
        }
        p.setVolume(remain);
        positionInfoRepository.save(p);
        refreshSellableVolume(p, accountId);
    }

    /** A 股 T+1：当日买入份额不计入可卖数量。 */
    void refreshSellableVolume(PositionInfo p, Long accountId) {
        LocalDate today = LocalDate.now();
        int boughtToday = tradingLogRepository.sumBuyVolumeBetween(
                accountId,
                p.getStockCode(),
                p.getExchangeType(),
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        );
        int sellable = Math.max(0, p.getVolume() - boughtToday);
        p.setAvailableVolume(sellable);
        positionInfoRepository.save(p);
    }

    private void refreshPositionPrice(PositionInfo p) {
        marketDataRepository.findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(p.getStockCode(), p.getExchangeType())
                .ifPresent(bar -> {
                    BigDecimal price = bar.getClosePrice();
                    p.setCurrentPrice(price);
                    p.setMarketValue(price.multiply(BigDecimal.valueOf(p.getVolume())));
                    if (p.getAvgCost() != null && p.getAvgCost().compareTo(BigDecimal.ZERO) > 0) {
                        p.setProfitLoss(price.subtract(p.getAvgCost()).multiply(BigDecimal.valueOf(p.getVolume())));
                        p.setProfitRate(price.subtract(p.getAvgCost()).divide(p.getAvgCost(), 4, RoundingMode.HALF_UP));
                    }
                    positionInfoRepository.save(p);
                });
    }

    private void refreshAccountMetrics(UserAccount account) {
        List<PositionInfo> positions = positionInfoRepository.findByAccountIdOrderByStockCodeAsc(account.getId());
        BigDecimal posValue = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (PositionInfo p : positions) {
            refreshPositionPrice(p);
            posValue = posValue.add(p.getMarketValue() != null ? p.getMarketValue() : BigDecimal.ZERO);
            totalProfit = totalProfit.add(p.getProfitLoss() != null ? p.getProfitLoss() : BigDecimal.ZERO);
        }
        account.setPositionValue(posValue);
        account.setTotalAsset(account.getAvailableCash().add(posValue));
        account.setTotalProfit(totalProfit);
        userAccountRepository.save(account);
    }

    private static int parseInt(Object raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record PriceResolve(BigDecimal price, String source, LocalDate tradeDate) {}
}
