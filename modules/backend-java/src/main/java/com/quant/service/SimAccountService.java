package com.quant.service;

import com.quant.config.AuthProperties;
import com.quant.entity.*;
import com.quant.exception.BusinessException;
import com.quant.repository.*;
import com.quant.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SimAccountService {

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.0003");
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.001");

    private final AuthProperties authProperties;
    private final AuthService authService;
    private final UserAccountRepository userAccountRepository;
    private final PositionInfoRepository positionInfoRepository;
    private final TradingLogRepository tradingLogRepository;
    private final MarketDataRepository marketDataRepository;
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
            refreshPositionPrice(p);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", p.getStockCode());
            m.put("name", p.getStockName());
            m.put("exchangeType", p.getExchangeType());
            m.put("volume", p.getVolume());
            m.put("avgPrice", p.getAvgCost());
            m.put("currentPrice", p.getCurrentPrice());
            m.put("marketValue", p.getMarketValue());
            m.put("profitLoss", p.getProfitLoss());
            m.put("profitRate", p.getProfitRate());
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
        if (stockCode.isBlank() || volume <= 0 || volume % 100 != 0) {
            throw new BusinessException("股票代码不能为空，数量须为 100 的整数倍");
        }
        if (!"buy".equals(action) && !"sell".equals(action)) {
            throw new BusinessException("action 仅支持 buy / sell");
        }

        BigDecimal price = resolvePrice(stockCode, exchange, request.get("price"));
        String stockName = stockInfoRepository.findByStockCodeAndExchangeType(stockCode, exchange)
                .map(StockInfo::getStockName)
                .orElse(stockCode);
        BigDecimal amount = price.multiply(BigDecimal.valueOf(volume));
        BigDecimal commission = amount.multiply(COMMISSION_RATE).setScale(4, RoundingMode.HALF_UP);

        if ("buy".equals(action)) {
            BigDecimal cost = amount.add(commission);
            if (account.getAvailableCash().compareTo(cost) < 0) {
                throw new BusinessException("可用资金不足");
            }
            account.setAvailableCash(account.getAvailableCash().subtract(cost));
            upsertPositionBuy(account.getId(), stockCode, exchange, stockName, volume, price);
        } else {
            PositionInfo pos = positionInfoRepository.findByAccountIdAndStockCodeAndExchangeType(account.getId(), stockCode, exchange)
                    .orElseThrow(() -> new BusinessException("无可用持仓"));
            if (pos.getAvailableVolume() < volume) {
                throw new BusinessException("可用持仓不足");
            }
            BigDecimal stamp = amount.multiply(STAMP_DUTY_RATE).setScale(4, RoundingMode.HALF_UP);
            account.setAvailableCash(account.getAvailableCash().add(amount).subtract(commission).subtract(stamp));
            upsertPositionSell(pos, volume);
            commission = commission.add(stamp);
        }

        userAccountRepository.save(account);
        refreshAccountMetrics(account);

        TradingLog log = new TradingLog();
        log.setOrderId("ORD" + System.currentTimeMillis());
        log.setStockCode(stockCode);
        log.setStockName(stockName);
        log.setExchangeType(exchange);
        log.setTradeType("buy".equals(action) ? 1 : 2);
        log.setTradePrice(price);
        log.setTradeVolume(volume);
        log.setTradeAmount(amount);
        log.setCommission(commission);
        log.setAccountId(account.getId());
        log.setUserId(userId);
        log.setOrderStatus(3);
        log.setOrderTime(LocalDateTime.now());
        log.setTradeTime(LocalDateTime.now());
        tradingLogRepository.save(log);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", log.getOrderId());
        result.put("status", "filled");
        result.put("action", action);
        result.put("price", price);
        result.put("volume", volume);
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

    private UserAccount resolveAccount(Long userId) {
        authService.ensureSimAccount(userId);
        return userAccountRepository.findFirstByUserIdAndStatus(userId, 1)
                .orElseThrow(() -> new BusinessException("模拟账户不存在"));
    }

    private BigDecimal resolvePrice(String stockCode, String exchange, Object priceObj) {
        if (priceObj != null) {
            return new BigDecimal(String.valueOf(priceObj));
        }
        return marketDataRepository.findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(stockCode, exchange)
                .map(MarketData::getClosePrice)
                .orElseThrow(() -> new BusinessException("无法获取最新收盘价，请指定 price"));
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
            p.setAvailableVolume(newVol);
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
            p.setAvailableVolume(volume);
            p.setAvgCost(price);
            p.setCurrentPrice(price);
            p.setFirstBuyDate(LocalDate.now());
            positionInfoRepository.save(p);
        }
    }

    private void upsertPositionSell(PositionInfo p, int volume) {
        int remain = p.getVolume() - volume;
        if (remain <= 0) {
            positionInfoRepository.delete(p);
            return;
        }
        p.setVolume(remain);
        p.setAvailableVolume(remain);
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
}
