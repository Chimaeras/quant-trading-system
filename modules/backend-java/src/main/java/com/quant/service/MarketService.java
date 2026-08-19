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
        int pageSize = Math.max(1, size);
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
}
