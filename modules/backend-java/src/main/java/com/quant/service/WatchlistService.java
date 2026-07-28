package com.quant.service;

import com.quant.entity.StockInfo;
import com.quant.entity.WatchlistItem;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.WatchlistItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
