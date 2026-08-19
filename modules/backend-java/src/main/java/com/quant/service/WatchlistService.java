package com.quant.service;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.StockInfo;
import com.quant.entity.WatchlistItem;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.WatchlistItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自选股池管理：添加/删除/列表。
 *
 * <p>口径：仅记录用户关注标的，不涉及盯盘实时推送（WebSocket 后置）；
 * 列表返回时附带 stock_info 的名称与行业，便于前端展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistItemRepository watchlistItemRepository;
    private final StockInfoRepository stockInfoRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId) {
        return watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> add(Long userId, String stockCode, String exchangeType, String note) {
        if (stockCode == null || stockCode.isBlank() || exchangeType == null || exchangeType.isBlank()) {
            throw new IllegalArgumentException("stockCode 与 exchangeType 不能为空");
        }
        // 已存在则更新 note（幂等添加）
        WatchlistItem item = watchlistItemRepository
                .findByUserIdAndStockCodeAndExchangeType(userId, stockCode.trim(), exchangeType.trim())
                .orElseGet(() -> {
                    WatchlistItem w = new WatchlistItem();
                    w.setUserId(userId);
                    w.setStockCode(stockCode.trim());
                    w.setExchangeType(exchangeType.trim());
                    return w;
                });
        if (note != null && !note.isBlank()) {
            item.setNote(note.trim());
        }
        // 自动补全名称
        if (item.getStockName() == null || item.getStockName().isBlank()) {
            stockInfoRepository.findByStockCodeAndExchangeType(stockCode.trim(), exchangeType.trim())
                    .map(StockInfo::getStockName)
                    .ifPresent(item::setStockName);
        }
        watchlistItemRepository.save(item);
        log.info("自选股添加 userId={} code={} exchange={}", userId, stockCode, exchangeType);
        return toMap(item);
    }

    @Transactional
    public Map<String, Object> remove(Long userId, String stockCode, String exchangeType) {
        long deleted = watchlistItemRepository
                .deleteByUserIdAndStockCodeAndExchangeType(userId, stockCode, exchangeType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);
        out.put("stock_code", stockCode);
        out.put("exchange_type", exchangeType);
        out.put("deleted", deleted > 0);
        return out;
    }

    /**
     * 批量添加自选股：接收「代码,交易所」对列表，逐个幂等添加。
     * 交易所可省略，省略时按代码前缀推断（6→SSE，0/3→SZSE，4/8/92→BSE）。
     */
    @Transactional
    public Map<String, Object> batchAdd(Long userId, List<String[]> pairs) {
        int added = 0;
        int skipped = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        if (pairs != null) {
            for (String[] p : pairs) {
                String code = p != null && p.length > 0 ? p[0].trim() : "";
                String exchange = p != null && p.length > 1 ? p[1].trim() : "";
                if (code.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (exchange.isEmpty()) {
                    exchange = inferExchange(code);
                }
                try {
                    add(userId, code, exchange, null);
                    added++;
                } catch (Exception e) {
                    skipped++;
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("stock_code", code);
                    d.put("exchange_type", exchange);
                    d.put("error", e.getMessage());
                    details.add(d);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);
        out.put("added", added);
        out.put("skipped", skipped);
        out.put("errors", details);
        out.put("note", "批量添加为幂等操作，已存在的标的更新备注");
        return out;
    }

    /** 按代码前缀推断交易所。 */
    private static String inferExchange(String stockCode) {
        if (stockCode == null || stockCode.isEmpty()) return "SZSE";
        char c = stockCode.charAt(0);
        if (c == '6') return "SSE";
        if (c == '4' || c == '8' || stockCode.startsWith("92")) return "BSE";
        return "SZSE";
    }

    private Map<String, Object> toMap(WatchlistItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("user_id", item.getUserId());
        m.put("stock_code", item.getStockCode());
        m.put("exchange_type", item.getExchangeType());
        m.put("stock_name", item.getStockName());
        m.put("note", item.getNote());
        m.put("created_at", item.getCreatedAt() != null ? item.getCreatedAt().toString() : "");
        return m;
    }

    /**
     * 自选股与选股信号联动：对自选股列表中每只标的，
     * 查询其是否出现在各策略最新成功选股结果中，附命中策略与排名。
     *
     * <p>口径：基于已持久化 selector_result（SUCCESS），无未来函数；
     * 信号日 = 选股结果 tradeDate；仅展示命中情况，不构成买卖建议。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> signalCoverage(Long userId) {
        List<WatchlistItem> items = watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);

        if (items.isEmpty()) {
            out.put("trade_date", "");
            out.put("total", 0);
            out.put("hit_count", 0);
            out.put("items", List.of());
            out.put("note", "自选股为空，请先添加自选股");
            return out;
        }

        // 各策略最新成功结果（同策略同日取最新一条）
        List<SelectorResult> latest = selectorResultRepository.findLatestSuccessPerStrategy();
        Map<String, SelectorResult> latestPerStrategy = new LinkedHashMap<>();
        for (SelectorResult r : latest) {
            latestPerStrategy.putIfAbsent(r.getStrategyType(), r);
        }

        String tradeDate = latestPerStrategy.values().stream()
                .map(SelectorResult::getTradeDate)
                .max(java.time.LocalDate::compareTo)
                .map(java.time.LocalDate::toString)
                .orElse("");

        List<String> resultIds = latestPerStrategy.values().stream()
                .map(SelectorResult::getResultId)
                .toList();
        List<SelectorResultItem> allItems = resultIds.isEmpty()
                ? List.of()
                : selectorResultItemRepository.findByResultIdInOrderByRankNo(resultIds);

        // 按 resultId 分组
        Map<String, List<SelectorResultItem>> itemsByResultId = new LinkedHashMap<>();
        for (SelectorResultItem it : allItems) {
            itemsByResultId.computeIfAbsent(it.getResultId(), k -> new ArrayList<>()).add(it);
        }

        List<Map<String, Object>> resultList = new ArrayList<>();
        int hitCount = 0;
        for (WatchlistItem w : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stock_code", w.getStockCode());
            m.put("exchange_type", w.getExchangeType());
            m.put("stock_name", w.getStockName());

            List<Map<String, Object>> hits = new ArrayList<>();
            for (Map.Entry<String, SelectorResult> e : latestPerStrategy.entrySet()) {
                String resultId = e.getValue().getResultId();
                List<SelectorResultItem> dayItems = itemsByResultId.getOrDefault(resultId, List.of());
                SelectorResultItem match = dayItems.stream()
                        .filter(it -> w.getStockCode().equals(it.getStockCode())
                                && w.getExchangeType().equals(it.getExchangeType()))
                        .findFirst().orElse(null);
                if (match != null) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("strategy", e.getKey());
                    h.put("rank_no", match.getRankNo());
                    h.put("final_score", match.getFinalScore());
                    hits.add(h);
                }
            }
            m.put("hit_strategies", hits);
            m.put("hit_count", hits.size());
            if (!hits.isEmpty()) hitCount++;
            resultList.add(m);
        }

        out.put("trade_date", tradeDate);
        out.put("total", items.size());
        out.put("hit_count", hitCount);
        out.put("items", resultList);
        out.put("note", "基于各策略最新成功选股结果；信号日为选股结果 tradeDate；仅展示命中情况，不构成买卖建议");
        return out;
    }
}
