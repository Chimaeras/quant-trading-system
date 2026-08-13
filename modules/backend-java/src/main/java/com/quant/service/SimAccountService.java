package com.quant.service;

import com.quant.entity.*;
import com.quant.exception.BusinessException;
import com.quant.repository.*;
import com.quant.security.UserIdResolver;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;
import com.quant.service.execution.TradingFeePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 模拟账户（MP-026 / V2-MP-021）：与研究回测共用成本口径；省略 price 时须指定 tradeDate，
 * 优先 raw 价并标注 price_source（V2-MP-014）。
 *
 * <p>V2-MP-021 历史时钟 / T+1 / 费用语义：
 * <ul>
 *   <li><b>operationDate（仿真时钟）</b>：所有业务时间一律来自订单 tradeDate；仅在线模式
 *       （未传 sessionId）才使用系统交易日 LocalDate.now()。历史回填必须挂到演练会话。</li>
 *   <li><b>lot 级 T+1</b>：每次买入生成 {@link PositionLot}（acquisitionTradeDate +
 *       remainingVolume），可卖量 = 建仓日严格早于目标交易日的批次未卖量之和，卖出按
 *       acquisitionTradeDate FIFO 消耗。不再用 LocalDate.now() 与当天日志，不再用单一
 *       firstBuyDate 表示多批持仓。</li>
 *   <li><b>会话隔离</b>：历史订单携带 sessionId（sim_session），资金/持仓/订单按
 *       sessionId 隔离，绝不移入在线账户（session_id=0 哨兵）。</li>
 *   <li><b>费用</b>：统一 {@link TradingFeePolicy}，含 5 元最低佣金、印花税、滑点。</li>
 *   <li><b>资产刷新 mark source</b>：在线用最新行情收盘价；演练会话用 as-of 交易日
 *       （≤ 会话最晚成交日）的最近收盘价，绝不用「今天最新价」标记历史持仓。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SimAccountService {

    private static final int LOT_SIZE = TradingFeePolicy.LOT_SIZE;
    /** 在线账户会话哨兵：历史演练会话 id 均 &gt; 0。 */
    private static final long LIVE_SESSION = 0L;

    private final AuthService authService;
    private final UserAccountRepository userAccountRepository;
    private final PositionInfoRepository positionInfoRepository;
    private final TradingLogRepository tradingLogRepository;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final StockInfoRepository stockInfoRepository;
    private final TradingCalendarService tradingCalendarService;
    private final SimulationSessionRepository simulationSessionRepository;
    private final PositionLotRepository positionLotRepository;
    private final UserIdResolver userIdResolver;

    public Map<String, Object> getAccountInfo(Long userId, Long sessionId) {
        UserAccount account = resolveAccount(userId);
        Long scope = sessionScope(sessionId);
        if (scope != LIVE_SESSION) {
            SimulationSession s = requireSession(account.getId(), scope);
            refreshSessionMetrics(s, account.getId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", account.getId());
            data.put("sessionId", s.getId());
            data.put("sessionName", s.getSessionName());
            data.put("mode", s.getMode());
            data.put("totalAsset", nz(s.getAvailableCash()).add(nz(s.getPositionValue())));
            data.put("cash", s.getAvailableCash());
            data.put("positionValue", s.getPositionValue());
            data.put("todayPnl", BigDecimal.ZERO);
            data.put("totalProfit", s.getTotalProfit());
            data.put("positionCount", positionInfoRepository
                    .findByAccountIdAndSessionIdOrderByStockCodeAsc(account.getId(), scope).size());
            return data;
        }
        refreshAccountMetrics(account);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", account.getId());
        data.put("sessionId", LIVE_SESSION);
        data.put("mode", "ONLINE");
        data.put("totalAsset", account.getTotalAsset());
        data.put("cash", account.getAvailableCash());
        data.put("positionValue", account.getPositionValue());
        data.put("todayPnl", account.getTodayProfit());
        data.put("totalProfit", account.getTotalProfit());
        data.put("positionCount", positionInfoRepository
                .findByAccountIdAndSessionIdOrderByStockCodeAsc(account.getId(), LIVE_SESSION).size());
        return data;
    }

    public List<Map<String, Object>> listPositions(Long userId, Long sessionId) {
        UserAccount account = resolveAccount(userId);
        Long scope = sessionScope(sessionId);
        LocalDate asOf = scope != LIVE_SESSION ? sessionAsOfDate(account.getId(), scope) : LocalDate.now();
        List<PositionInfo> positions = positionInfoRepository
                .findByAccountIdAndSessionIdOrderByStockCodeAsc(account.getId(), scope);
        List<Map<String, Object>> list = new ArrayList<>();
        for (PositionInfo p : positions) {
            refreshSellableVolume(p, account.getId(), scope, asOf);
            refreshPositionPrice(p, account.getId(), scope, asOf);
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
            m.put("firstBuyDate", p.getFirstBuyDate() != null ? p.getFirstBuyDate().toString() : null);
            m.put("lotCount", positionLotRepository.countByAccountIdAndSessionIdAndStockCodeAndExchangeType(
                    account.getId(), scope, p.getStockCode(), p.getExchangeType()));
            list.add(m);
        }
        return list;
    }

    /** 账户订单/成交历史（最近 50 条，按时间倒序；可切换演练会话）。 */
    public List<Map<String, Object>> orderHistory(Long userId, Long sessionId) {
        UserAccount account = resolveAccount(userId);
        Long scope = sessionScope(sessionId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TradingLog t : tradingLogRepository
                .findTop50ByAccountIdAndSessionIdOrderByTradeTimeDesc(account.getId(), scope)) {
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
            m.put("priceSource", t.getPriceSource());
            m.put("sessionId", t.getSessionId());
            list.add(m);
        }
        return list;
    }

    // ---------- 历史演练会话 ----------

    /** 创建历史演练会话：资金从指定 cash 或在线账户当前可用资金快照，与在线账户隔离。 */
    @Transactional
    public Map<String, Object> createSession(Long userId, Map<String, Object> request) {
        UserAccount account = resolveAccount(userId);
        LocalDate startDate = parseDateOrNull(request.get("startDate"));
        if (startDate == null) {
            throw new BusinessException("演练会话必须指定 startDate（起始交易日）");
        }
        if (!tradingCalendarService.isTradingDay(startDate)) {
            throw new BusinessException("起始日不是交易日（请先初始化交易日历，见 /api/data/trading-calendar/init）");
        }
        BigDecimal baseCash;
        Object cashObj = request.get("cash");
        if (cashObj != null && !String.valueOf(cashObj).isBlank()) {
            baseCash = new BigDecimal(String.valueOf(cashObj).trim());
        } else {
            baseCash = nz(account.getAvailableCash());
        }
        SimulationSession s = new SimulationSession();
        s.setUserId(userId);
        s.setAccountId(account.getId());
        s.setSessionName(String.valueOf(request.getOrDefault("name", "历史演练")));
        s.setMode("HISTORICAL");
        s.setStatus("ACTIVE");
        s.setBaseCash(baseCash);
        s.setAvailableCash(baseCash);
        s.setStartDate(startDate);
        simulationSessionRepository.save(s);
        return sessionToMap(s);
    }

    /** 演练会话列表（新建在前）。 */
    public List<Map<String, Object>> listSessions(Long userId) {
        UserAccount account = resolveAccount(userId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SimulationSession s : simulationSessionRepository.findByAccountIdOrderByIdDesc(account.getId())) {
            list.add(sessionToMap(s));
        }
        return list;
    }

    /** 演练会话详情：会话元信息 + 该会话持仓 + 该会话订单 + as-of 交易日。 */
    public Map<String, Object> getSessionDetail(Long userId, Long sessionId) {
        UserAccount account = resolveAccount(userId);
        SimulationSession s = requireSession(account.getId(), sessionId);
        refreshSessionMetrics(s, account.getId());
        Map<String, Object> out = sessionToMap(s);
        out.put("asOfDate", sessionAsOfDate(account.getId(), sessionId).toString());
        out.put("positions", listPositions(userId, sessionId));
        out.put("orders", orderHistory(userId, sessionId));
        return out;
    }

    /** 关闭演练会话：禁止新增订单，endDate = 会话最晚成交日。 */
    @Transactional
    public Map<String, Object> closeSession(Long userId, Long sessionId) {
        UserAccount account = resolveAccount(userId);
        SimulationSession s = requireSession(account.getId(), sessionId);
        if ("CLOSED".equals(s.getStatus())) {
            throw new BusinessException("会话已关闭");
        }
        s.setStatus("CLOSED");
        s.setEndDate(sessionAsOfDate(account.getId(), sessionId));
        simulationSessionRepository.save(s);
        return sessionToMap(s);
    }

    // ---------- 下单 ----------

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

        Long sessionId = sessionScope(parseLong(request.get("sessionId")));
        boolean historical = sessionId != LIVE_SESSION;
        SimulationSession session = null;
        if (historical) {
            session = requireSession(account.getId(), sessionId);
            if (!"ACTIVE".equals(session.getStatus())) {
                return reject("SESSION_CLOSED", "演练会话已关闭，禁止新增订单");
            }
        }

        // 1) 仿真时钟：业务时间一律来自订单；在线模式才使用系统交易日
        LocalDate tradeDate = parseDateOrNull(request.get("tradeDate"));
        LocalDate operationDate;
        if (historical) {
            if (tradeDate == null) {
                return reject("HISTORICAL_DATE_REQUIRED", "历史演练必须指定 tradeDate（仿真交易日）");
            }
            if (tradeDate.isBefore(session.getStartDate())) {
                return reject("BEFORE_SESSION_START", "tradeDate 早于会话起始日 " + session.getStartDate());
            }
            if (!tradingCalendarService.isTradingDay(tradeDate)) {
                return reject("NOT_TRADING_DAY", "tradeDate=" + tradeDate + " 不是交易日（交易日历未覆盖该日）");
            }
            operationDate = tradeDate;
        } else {
            if (tradeDate != null && !tradeDate.equals(LocalDate.now())) {
                return reject("HISTORICAL_SESSION_REQUIRED",
                        "历史回填须先创建演练会话并传 sessionId，禁止把历史订单混入在线账户");
            }
            operationDate = LocalDate.now();
        }

        PriceResolve priceResolve;
        try {
            priceResolve = resolvePrice(stockCode, exchange, request.get("price"), operationDate);
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

        BigDecimal execPrice = "buy".equals(action)
                ? TradingFeePolicy.buyExecPrice(price)
                : TradingFeePolicy.sellExecPrice(price);
        BigDecimal amount = execPrice.multiply(BigDecimal.valueOf(volume));
        TradingFeePolicy.FeeBreakdown fee = TradingFeePolicy.breakdown(amount, "sell".equals(action));
        BigDecimal commission = fee.commission();
        BigDecimal stamp = fee.stampDuty();

        String orderId = "ORD" + System.currentTimeMillis() + (historical ? "_S" + sessionId : "");
        List<Map<String, Object>> lotBreakdown = new ArrayList<>();

        if ("buy".equals(action)) {
            BigDecimal cost = amount.add(commission);
            if (historical) {
                if (nz(session.getAvailableCash()).compareTo(cost) < 0) {
                    return reject("INSUFFICIENT_CASH", "可用资金不足");
                }
                session.setAvailableCash(nz(session.getAvailableCash()).subtract(cost));
            } else {
                if (nz(account.getAvailableCash()).compareTo(cost) < 0) {
                    return reject("INSUFFICIENT_CASH", "可用资金不足");
                }
                account.setAvailableCash(nz(account.getAvailableCash()).subtract(cost));
            }
            PositionLot lot = new PositionLot();
            lot.setAccountId(account.getId());
            lot.setSessionId(sessionId);
            lot.setStockCode(stockCode);
            lot.setStockName(stockName);
            lot.setExchangeType(exchange);
            lot.setOrderId(orderId);
            lot.setAcquisitionTradeDate(operationDate);
            lot.setVolume(volume);
            lot.setRemainingVolume(volume);
            lot.setCostPrice(execPrice);
            lot.setPriceSource(priceResolve.source());
            positionLotRepository.save(lot);
            lotBreakdown.add(lotToMap(lot));
            recomputePosition(account.getId(), sessionId, stockCode, exchange, stockName, operationDate);
        } else {
            PositionInfo pos = positionInfoRepository
                    .findByAccountIdAndSessionIdAndStockCodeAndExchangeType(account.getId(), sessionId, stockCode, exchange)
                    .orElse(null);
            if (pos == null) {
                return reject("NO_POSITION", "无可用持仓");
            }
            int sellable = computeSellable(account.getId(), sessionId, stockCode, exchange, operationDate);
            if (sellable < volume) {
                return reject("T1_FROZEN",
                        "可用持仓不足（A 股 T+1：仅 " + operationDate + " 之前建仓的批次可卖出，且整手 100 股）");
            }
            int toSell = volume;
            for (PositionLot lot : positionLotRepository
                    .findByAccountIdAndSessionIdAndStockCodeAndExchangeTypeAndRemainingVolumeGreaterThanOrderByAcquisitionTradeDateAscIdAsc(
                            account.getId(), sessionId, stockCode, exchange, 0)) {
                if (toSell <= 0) {
                    break;
                }
                if (lot.getAcquisitionTradeDate() == null || !lot.getAcquisitionTradeDate().isBefore(operationDate)) {
                    continue; // T+1：当日买入批次不可卖
                }
                int take = Math.min(toSell, lot.getRemainingVolume());
                lot.setRemainingVolume(lot.getRemainingVolume() - take);
                toSell -= take;
                positionLotRepository.save(lot);
                lotBreakdown.add(lotToMap(lot));
            }
            BigDecimal proceeds = amount.subtract(commission).subtract(stamp);
            if (historical) {
                session.setAvailableCash(nz(session.getAvailableCash()).add(proceeds));
            } else {
                account.setAvailableCash(nz(account.getAvailableCash()).add(proceeds));
            }
            recomputePosition(account.getId(), sessionId, stockCode, exchange, stockName, operationDate);
        }

        if (historical) {
            simulationSessionRepository.save(session);
        } else {
            userAccountRepository.save(account);
        }

        TradingLog log = new TradingLog();
        log.setOrderId(orderId);
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
        log.setSessionId(sessionId);
        log.setUserId(userId);
        log.setPriceSource(priceResolve.source());
        log.setOrderStatus(3);
        LocalDateTime tradeTs = operationDate.atTime(15, 0);
        log.setOrderTime(tradeTs);
        log.setTradeTime(tradeTs);
        tradingLogRepository.save(log);

        if (historical) {
            refreshSessionMetrics(session, account.getId());
        } else {
            refreshAccountMetrics(account);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("status", "FILLED");
        result.put("action", action);
        result.put("price", execPrice);
        result.put("volume", volume);
        result.put("price_source", priceResolve.source());
        result.put("operation_date", operationDate.toString());
        result.put("trade_date", operationDate.toString());
        result.put("commission", commission);
        result.put("stamp_duty", stamp);
        result.put("slippage_rate", TradingFeePolicy.SLIPPAGE_RATE);
        result.put("min_commission", TradingFeePolicy.MIN_COMMISSION);
        result.put("cost_version", TradingFeePolicy.VERSION);
        result.put("fee_version", TradingFeePolicy.VERSION);
        result.put("lot_breakdown", lotBreakdown);
        result.put("fee_breakdown", Map.of(
                "commission", commission,
                "stamp_duty", stamp,
                "min_commission", TradingFeePolicy.MIN_COMMISSION,
                "min_applied", fee.minApplied(),
                "slippage_rate", TradingFeePolicy.SLIPPAGE_RATE,
                "version", TradingFeePolicy.VERSION));
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

    /**
     * 委托给统一的 {@link UserIdResolver}，避免与 UserIdResolver 逻辑重复（单一真相源）。
     * 保留 public 方法签名以兼容现有 Controller 调用。
     */
    public Long resolveUserId(Long explicitUserId) {
        return userIdResolver.resolve(explicitUserId);
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
     * 省略 price 时必须提供 tradeDate：成交价只读 MarketPriceRaw.close（V2-MP-014），
     * 缺 raw 一律拒绝，禁止用 qfq 前复权价"救活"成交。禁止静默使用「最新价」而不标注来源。
     */
    private PriceResolve resolvePrice(String stockCode, String exchange, Object priceObj, LocalDate operationDate) {
        if (priceObj != null && !String.valueOf(priceObj).isBlank()) {
            return new PriceResolve(new BigDecimal(String.valueOf(priceObj)), "CLIENT_SPECIFIED", operationDate);
        }
        if (operationDate == null) {
            throw new BusinessException("未指定 price 时必须提供 tradeDate（禁止静默使用未标注的最新价）");
        }
        Optional<MarketPriceRaw> raw = marketPriceRawRepository
                .findByStockCodeAndExchangeTypeAndTradeDate(stockCode, exchange, operationDate);
        if (raw.isPresent() && raw.get().getClosePrice() != null) {
            return new PriceResolve(raw.get().getClosePrice(), "market_price_raw", operationDate);
        }
        throw new BusinessException("tradeDate=" + operationDate
                + " 无可用 market_price_raw 收盘价，拒绝按 qfq 成交（V2-MP-014）");
    }

    // ---------- 持仓批次与 T+1 ----------

    /** 可卖量 = 建仓日严格早于目标交易日的批次未卖量之和（A 股 T+1，与墙上日期无关）。 */
    private int computeSellable(Long accountId, Long sessionId, String code, String exchange, LocalDate asOf) {
        int sellable = 0;
        for (PositionLot lot : positionLotRepository
                .findByAccountIdAndSessionIdAndStockCodeAndExchangeType(accountId, sessionId, code, exchange)) {
            if (lot.getRemainingVolume() != null && lot.getRemainingVolume() > 0
                    && lot.getAcquisitionTradeDate() != null
                    && lot.getAcquisitionTradeDate().isBefore(asOf)) {
                sellable += lot.getRemainingVolume();
            }
        }
        return sellable;
    }

    private void refreshSellableVolume(PositionInfo p, Long accountId, Long sessionId, LocalDate asOf) {
        int sellable = computeSellable(accountId, sessionId, p.getStockCode(), p.getExchangeType(), asOf);
        p.setAvailableVolume(sellable);
        positionInfoRepository.save(p);
    }

    /**
     * 从全部批次重算持仓聚合：volume=Σ剩余、avgCost=Σ(成本×原量)/Σ原量（摊薄成本，保留已卖批
     * 次）、firstBuyDate=最早未卖批次建仓日。持仓为 0 时删除 position_info。
     */
    private void recomputePosition(Long accountId, Long sessionId, String code, String exchange, String name, LocalDate asOf) {
        List<PositionLot> lots = positionLotRepository
                .findByAccountIdAndSessionIdAndStockCodeAndExchangeType(accountId, sessionId, code, exchange);
        int totalVolume = 0;
        int remaining = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        LocalDate earliest = null;
        for (PositionLot lot : lots) {
            totalVolume += lot.getVolume();
            remaining += lot.getRemainingVolume();
            if (lot.getCostPrice() != null) {
                totalCost = totalCost.add(lot.getCostPrice().multiply(BigDecimal.valueOf(lot.getVolume())));
            }
            if (lot.getRemainingVolume() > 0 && lot.getAcquisitionTradeDate() != null
                    && (earliest == null || lot.getAcquisitionTradeDate().isBefore(earliest))) {
                earliest = lot.getAcquisitionTradeDate();
            }
        }
        PositionInfo p = positionInfoRepository
                .findByAccountIdAndSessionIdAndStockCodeAndExchangeType(accountId, sessionId, code, exchange)
                .orElse(null);
        if (remaining <= 0) {
            if (p != null) {
                positionInfoRepository.delete(p);
            }
            return;
        }
        if (p == null) {
            p = new PositionInfo();
            p.setAccountId(accountId);
            p.setSessionId(sessionId);
            p.setStockCode(code);
            p.setStockName(name);
            p.setExchangeType(exchange);
        }
        p.setVolume(remaining);
        p.setAvgCost(totalVolume > 0
                ? totalCost.divide(BigDecimal.valueOf(totalVolume), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        p.setFirstBuyDate(earliest);
        p.setAvailableVolume(computeSellable(accountId, sessionId, code, exchange, asOf));
        positionInfoRepository.save(p);
        refreshPositionPrice(p, accountId, sessionId, asOf);
    }

    private static Map<String, Object> lotToMap(PositionLot lot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", lot.getOrderId());
        m.put("acquisition_trade_date", lot.getAcquisitionTradeDate() != null ? lot.getAcquisitionTradeDate().toString() : null);
        m.put("volume", lot.getVolume());
        m.put("remaining_volume", lot.getRemainingVolume());
        m.put("cost_price", lot.getCostPrice());
        return m;
    }

    // ---------- 资产刷新（mark source） ----------

    /**
     * 价格标记来源：在线账户取最新行情收盘价；演练会话取 as-of 交易日（≤ asOf）最近收盘价，
     * 绝不用「今天最新价」标记历史持仓。
     */
    private void refreshPositionPrice(PositionInfo p, Long accountId, Long sessionId, LocalDate asOf) {
        Optional<MarketData> markOpt = sessionId != LIVE_SESSION
                ? marketDataRepository
                        .findFirstByStockCodeAndExchangeTypeAndTradeDateLessThanOrderByTradeDateDesc(
                                p.getStockCode(), p.getExchangeType(), asOf.plusDays(1))
                : marketDataRepository.findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(
                        p.getStockCode(), p.getExchangeType());
        markOpt.ifPresent(bar -> {
            BigDecimal price = bar.getClosePrice();
            if (price == null || price.signum() <= 0) {
                return;
            }
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
        List<PositionInfo> positions = positionInfoRepository
                .findByAccountIdAndSessionIdOrderByStockCodeAsc(account.getId(), LIVE_SESSION);
        BigDecimal posValue = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (PositionInfo p : positions) {
            refreshPositionPrice(p, account.getId(), LIVE_SESSION, LocalDate.now());
            posValue = posValue.add(nz(p.getMarketValue()));
            totalProfit = totalProfit.add(nz(p.getProfitLoss()));
        }
        account.setPositionValue(posValue);
        account.setTotalAsset(nz(account.getAvailableCash()).add(posValue));
        account.setTotalProfit(totalProfit);
        userAccountRepository.save(account);
    }

    private void refreshSessionMetrics(SimulationSession session, Long accountId) {
        LocalDate asOf = sessionAsOfDate(accountId, session.getId());
        BigDecimal posValue = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        for (PositionInfo p : positionInfoRepository
                .findByAccountIdAndSessionIdOrderByStockCodeAsc(accountId, session.getId())) {
            refreshPositionPrice(p, accountId, session.getId(), asOf);
            posValue = posValue.add(nz(p.getMarketValue()));
            totalProfit = totalProfit.add(nz(p.getProfitLoss()));
        }
        session.setPositionValue(posValue);
        session.setTotalProfit(totalProfit);
        simulationSessionRepository.save(session);
    }

    /** 会话 as-of 交易日 = 会话最晚成交日；无订单时取会话起始日。 */
    private LocalDate sessionAsOfDate(Long accountId, Long sessionId) {
        LocalDateTime max = tradingLogRepository.maxTradeTimeByAccountAndSession(accountId, sessionId).orElse(null);
        if (max != null) {
            return max.toLocalDate();
        }
        SimulationSession s = simulationSessionRepository.findById(sessionId).orElse(null);
        return s != null && s.getStartDate() != null ? s.getStartDate() : LocalDate.now();
    }

    private SimulationSession requireSession(Long accountId, Long sessionId) {
        if (sessionId == null || sessionId == LIVE_SESSION) {
            throw new BusinessException("需要历史演练 sessionId");
        }
        return simulationSessionRepository.findByIdAndAccountId(sessionId, accountId)
                .orElseThrow(() -> new BusinessException("演练会话不存在"));
    }

    private static Map<String, Object> sessionToMap(SimulationSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getId());
        m.put("name", s.getSessionName());
        m.put("mode", s.getMode());
        m.put("status", s.getStatus());
        m.put("baseCash", s.getBaseCash());
        m.put("cash", s.getAvailableCash());
        m.put("positionValue", s.getPositionValue());
        m.put("totalProfit", s.getTotalProfit());
        m.put("startDate", s.getStartDate() != null ? s.getStartDate().toString() : null);
        m.put("endDate", s.getEndDate() != null ? s.getEndDate().toString() : null);
        return m;
    }

    private static Long sessionScope(Long sessionId) {
        return sessionId == null ? LIVE_SESSION : sessionId;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static int parseInt(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDateOrNull(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(raw).trim());
    }

    private record PriceResolve(BigDecimal price, String source, LocalDate tradeDate) {}
}
