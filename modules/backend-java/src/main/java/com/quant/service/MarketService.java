package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MarketService {

    /** 分页上限：/api/market/** 免鉴权，size 不封顶会被放大成全表水合。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final MarketDataRepository marketDataRepo;
    private final StockInfoRepository stockInfoRepo;

    /** 根据股票代码推断交易所 */
    private String inferExchangeType(String stockCode) {
        if (stockCode == null || stockCode.length() < 4) return "SZSE";
        String base = stockCode.split("\\.")[0];
        if (base.startsWith("4") || base.startsWith("8") || base.startsWith("92")) return "BSE";
        if (base.startsWith("6") || base.startsWith("5") || base.startsWith("9")) return "SSE";
        return "SZSE";
    }

    /** 获取股票详情(基础信息 + 最新行情) */
    public Map<String, Object> getStockDetail(String stockCode, String exchangeType) {
        String ex = (exchangeType != null && !exchangeType.isBlank()) ? exchangeType : inferExchangeType(stockCode);
        Map<String, Object> result = new HashMap<>();

        Optional<StockInfo> stockOpt = stockInfoRepo.findByStockCodeAndExchangeType(stockCode, ex)
                .or(() -> stockInfoRepo.findFirstByStockCode(stockCode));
        stockOpt.ifPresent(s -> {
            result.put("stock_code", s.getStockCode());
            result.put("stock_name", s.getStockName());
            result.put("exchange_type", s.getExchangeType());
            result.put("exchange_type_name", s.getExchangeTypeName());
            result.put("industry", s.getIndustry());
        });

        if (result.isEmpty()) {
            result.put("stock_code", stockCode);
            result.put("stock_name", stockCode);
            result.put("exchange_type", ex);
            result.put("exchange_type_name", ex.equals("SSE") ? "上交所" : (ex.equals("BSE") ? "北交所" : "深交所"));
        }

        String exFinal = (String) result.get("exchange_type");
        if (exFinal == null) exFinal = ex;
        marketDataRepo.findFirstByStockCodeAndExchangeTypeOrderByTradeDateDesc(stockCode, exFinal)
                .ifPresent(m -> {
                    result.put("trade_date", m.getTradeDate() != null ? m.getTradeDate().toString() : null);
                    result.put("open_price", m.getOpenPrice());
                    result.put("high_price", m.getHighPrice());
                    result.put("low_price", m.getLowPrice());
                    result.put("close_price", m.getClosePrice());
                    result.put("pre_close", m.getPreClose());
                    result.put("change_pct", m.getChangePct());
                    result.put("volume", m.getVolume());
                    result.put("amount", m.getAmount());
                    result.put("turnover_rate", m.getTurnoverRate());
                    result.put("pe_ratio", m.getPeRatio());
                    result.put("pb_ratio", m.getPbRatio());
                });

        return result;
    }

    /** 获取K线数据，支持日期范围 */
    public List<Map<String, Object>> getKlineData(String stockCode, String exchangeType,
                                                   LocalDate startDate, LocalDate endDate, int limit) {
        String ex = (exchangeType != null && !exchangeType.isBlank()) ? exchangeType : inferExchangeType(stockCode);

        if (startDate == null) startDate = LocalDate.now().minusYears(1);
        if (endDate == null) endDate = LocalDate.now();
        if (limit <= 0) limit = 500;

        List<MarketData> list = marketDataRepo.findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                stockCode, ex, startDate, endDate);

        // 若数据过多则取最近 limit 条
        if (list.size() > limit) {
            list = list.subList(list.size() - limit, list.size());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (MarketData m : list) {
            Map<String, Object> item = new HashMap<>();
            item.put("date", m.getTradeDate() != null ? m.getTradeDate().toString() : null);
            item.put("open", m.getOpenPrice() != null ? m.getOpenPrice().doubleValue() : null);
            item.put("high", m.getHighPrice() != null ? m.getHighPrice().doubleValue() : null);
            item.put("low", m.getLowPrice() != null ? m.getLowPrice().doubleValue() : null);
            item.put("close", m.getClosePrice() != null ? m.getClosePrice().doubleValue() : null);
            item.put("volume", m.getVolume() != null ? m.getVolume() : 0);
            item.put("amount", m.getAmount() != null ? m.getAmount().doubleValue() : null);
            item.put("change_pct", m.getChangePct() != null ? m.getChangePct().doubleValue() : null);
            item.put("turnover_rate", m.getTurnoverRate() != null ? m.getTurnoverRate().doubleValue() : null);
            result.add(item);
        }
        return result;
    }

    /** 获取股票列表(分页、搜索) */
    public Map<String, Object> getStockList(String keyword, String exchangeType, int page, int size) {
        int p = Math.max(0, page - 1);
        // /api/market/** 免鉴权，size 必须封顶：否则 size=2147483647 会全量水合 stock_info
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String k = keyword != null ? keyword.trim() : null;
        String ex = exchangeType != null ? exchangeType.trim() : null;

        Page<StockInfo> pageData = stockInfoRepo.search(
                k,
                ex,
                PageRequest.of(p, pageSize, Sort.by(Sort.Direction.ASC, "stockCode"))
        );
        long total = pageData.getTotalElements();
        List<StockInfo> pageList = pageData.getContent();
        List<Map<String, Object>> list = new ArrayList<>();
        for (StockInfo s : pageList) {
            Map<String, Object> m = new HashMap<>();
            m.put("stock_code", s.getStockCode());
            m.put("stock_name", s.getStockName());
            m.put("exchange_type", s.getExchangeType());
            m.put("exchange_type_name", s.getExchangeTypeName());
            m.put("industry", s.getIndustry());
            m.put("list_date", s.getListDate() != null ? s.getListDate().toString() : null);
            list.add(m);
        }
        return Map.of("list", list, "total", total);
    }

    /**
     * 批量证券交易规则快照。动态停牌/价格笼子仍需执行级行情确认；本接口只
     * 输出当前主数据能够证明的规则，并显式携带 limited 可靠度，禁止调用方
     * 把推断值冒充交易所实时状态。
     */
    public Map<String, Object> getSecurityRules(LocalDate asOf, int page, int size) {
        LocalDate effectiveDate = asOf != null ? asOf : LocalDate.now();
        int p = Math.max(0, page - 1);
        int pageSize = Math.min(Math.max(1, size), 2000);
        Page<StockInfo> pageData = stockInfoRepo.findAll(
                PageRequest.of(p, pageSize, Sort.by(Sort.Direction.ASC, "stockCode"))
        );
        List<Map<String, Object>> rules = new ArrayList<>();
        for (StockInfo stock : pageData.getContent()) {
            String code = stock.getStockCode();
            String exchange = stock.getExchangeType();
            String board = inferBoard(code, exchange);
            String name = Optional.ofNullable(stock.getStockName()).orElse("").trim();
            boolean st = name.toUpperCase(Locale.ROOT).startsWith("ST")
                    || name.toUpperCase(Locale.ROOT).startsWith("*ST");
            String status = switch (Optional.ofNullable(stock.getStatus()).orElse(1)) {
                case 0 -> "SUSPENDED";
                case -1 -> "DELISTED";
                default -> "ACTIVE";
            };
            long listDays = stock.getListDate() == null
                    ? -1L : Math.max(0L, java.time.temporal.ChronoUnit.DAYS.between(stock.getListDate(), effectiveDate));

            Double limitRate = st ? 0.05 : switch (board) {
                case "STAR", "CHINEXT" -> 0.20;
                case "BSE" -> 0.30;
                default -> 0.10;
            };
            String limitRule = "NORMAL";
            if (listDays >= 0 && listDays < 5) {
                // 新股前若干交易日规则与板块、上市方式及交易日历有关；当前
                // stock_info 不能可靠确定，必须交给执行级行情确认。
                limitRate = null;
                limitRule = "NEW_LISTING_EXECUTION_QUOTE_REQUIRED";
            }

            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("stock_code", code);
            rule.put("stock_name", name);
            rule.put("exchange_type", exchange);
            rule.put("board", board);
            rule.put("status", status);
            rule.put("is_st", st);
            rule.put("list_date", stock.getListDate() == null ? null : stock.getListDate().toString());
            rule.put("list_days", listDays);
            rule.put("tick_size", 0.01);
            rule.put("price_decimals", 2);
            rule.put("limit_rate", limitRate);
            rule.put("limit_rule", limitRule);
            rule.put("price_cage", board.equals("STAR") || board.equals("CHINEXT")
                    ? "EXECUTION_QUOTE_REQUIRED" : "NOT_APPLICABLE_OR_UNKNOWN");
            rule.put("buy_lot", 100);
            rule.put("odd_lot_sell", true);
            rule.put("settlement", "T+1");
            rule.put("effective_date", effectiveDate.toString());
            rule.put("rule_version", "CN_A_POLICY_V1");
            rule.put("source", "stock_info+versioned_exchange_policy");
            rule.put("reliability", "limited");
            rules.add(rule);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rules);
        result.put("total", pageData.getTotalElements());
        result.put("page", p + 1);
        result.put("size", pageSize);
        result.put("effective_date", effectiveDate.toString());
        result.put("rule_version", "CN_A_POLICY_V1");
        return result;
    }

    private String inferBoard(String stockCode, String exchangeType) {
        String code = Optional.ofNullable(stockCode).orElse("");
        if ("BSE".equalsIgnoreCase(exchangeType) || code.startsWith("4")
                || code.startsWith("8") || code.startsWith("92")) return "BSE";
        if (code.startsWith("688") || code.startsWith("689")) return "STAR";
        if (code.startsWith("300") || code.startsWith("301")) return "CHINEXT";
        return "MAIN";
    }
}
