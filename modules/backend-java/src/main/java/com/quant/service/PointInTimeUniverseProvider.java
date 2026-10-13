package com.quant.service;

import com.quant.entity.SecurityMasterHistory;
import com.quant.entity.StockInfo;
import com.quant.research.SecurityMasterHistoryService;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一按 as_of 提供证券主数据（PIT），供历史选股/打分/回测使用。
 *
 * <p>优先读取 {@code security_master_history}；若尚未初始化则回退当前 {@code stock_info}，
 * 并显式标记 {@code master_reliability=limited} / {@code PIT_LIMITED}，禁止当作 champion 证据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointInTimeUniverseProvider {

    public static final String RELIABILITY_LIMITED = "limited";
    public static final String RELIABILITY_OFFICIAL = "official";
    public static final String SOURCE_PIT_HISTORY = "security_master_history";
    public static final String SOURCE_STOCK_INFO_FALLBACK = "stock_info_fallback";

    private final SecurityMasterHistoryService securityMasterHistoryService;
    private final StockInfoRepository stockInfoRepository;

    /**
     * as_of 日生效的股票池视图（含来源与可靠性披露）。
     */
    public record UniverseView(
            LocalDate asOf,
            List<StockInfo> stocks,
            Map<String, StockInfo> byKey,
            String masterSource,
            String masterReliability,
            boolean pitLimited,
            int universeSize
    ) {
        public Map<String, Object> lineage() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("universe_as_of", asOf != null ? asOf.toString() : null);
            m.put("master_source", masterSource);
            m.put("master_reliability", masterReliability);
            m.put("pit_limited", pitLimited);
            m.put("universe_size", universeSize);
            if (pitLimited) {
                m.put("universe_gate", "PIT_LIMITED");
            }
            return m;
        }
    }

    @Transactional(readOnly = true)
    public UniverseView loadAsOf(LocalDate asOf) {
        if (asOf == null) {
            asOf = LocalDate.now();
        }
        List<SecurityMasterHistory> pit = securityMasterHistoryService.findEffectiveMasters(asOf);
        if (pit != null && !pit.isEmpty()) {
            List<StockInfo> stocks = new ArrayList<>(pit.size());
            Map<String, StockInfo> byKey = new HashMap<>(Math.max(16, pit.size() * 2));
            String worstReliability = RELIABILITY_OFFICIAL;
            for (SecurityMasterHistory h : pit) {
                // 已退市且退市日不晚于 asOf：不进入可交易池
                if (h.getDelistDate() != null && !h.getDelistDate().isAfter(asOf)) {
                    continue;
                }
                StockInfo s = toStockInfo(h, asOf);
                stocks.add(s);
                byKey.put(key(s.getStockCode(), s.getExchangeType()), s);
                if (RELIABILITY_LIMITED.equalsIgnoreCase(h.getReliability())) {
                    worstReliability = RELIABILITY_LIMITED;
                }
            }
            boolean limited = RELIABILITY_LIMITED.equals(worstReliability);
            log.debug("PIT universe asOf={} size={} reliability={}", asOf, stocks.size(), worstReliability);
            return new UniverseView(asOf, stocks, byKey, SOURCE_PIT_HISTORY, worstReliability, limited, stocks.size());
        }

        // 未初始化主数据历史：回退当前截面并强制 LIMITED 披露（禁止静默当历史权威）
        log.warn("security_master_history 在 asOf={} 无记录，回退 stock_info 当前截面（PIT_LIMITED）", asOf);
        List<StockInfo> all = stockInfoRepository.findAll();
        Map<String, StockInfo> byKey = new HashMap<>(Math.max(16, all.size() * 2));
        int droppedNotYetListed = 0;
        for (StockInfo s : all) {
            if (s.getStockCode() == null || s.getExchangeType() == null) {
                continue;
            }
            // D-PIT-01（2026-10-13 修正）：回退路径此前完全不做日期过滤，连 list_date 都不看，
            // 于是「asOf 当时尚未上市」的标的也会进入历史截面，构成明确的前视泄漏。
            // 这里补上唯一可在此路径正确判定的时点条件：上市日必须不晚于 asOf
            // （list_date 为 null 时无法判定，保守放行并由 pit_limited=true 整体披露）。
            LocalDate listDate = s.getListDate();
            if (listDate != null && listDate.isAfter(asOf)) {
                droppedNotYetListed++;
                continue;
            }
            byKey.put(key(s.getStockCode(), s.getExchangeType()), s);
        }
        if (droppedNotYetListed > 0) {
            log.info("回退截面剔除 {} 只在 asOf={} 尚未上市的标的（前视防护）", droppedNotYetListed, asOf);
        }
        // 已知残留局限（本路径无法修复，需真实历史主数据）：stock_info 是「今天」的截面，
        // 已退市标的整体缺失（幸存者偏差），且 status 无历史（当前全部为 1）。
        // 唯一修复途径是回填 security_master_history（含 valid_from/valid_to/delist_date）。
        return new UniverseView(
                asOf,
                new ArrayList<>(byKey.values()),
                byKey,
                SOURCE_STOCK_INFO_FALLBACK,
                RELIABILITY_LIMITED,
                true,
                byKey.size());
    }

    private static StockInfo toStockInfo(SecurityMasterHistory h, LocalDate asOf) {
        StockInfo s = new StockInfo();
        s.setStockCode(h.getStockCode());
        s.setExchangeType(h.getExchangeType());
        s.setStockName(h.getStockName());
        s.setIndustry(h.getIndustry());
        s.setListDate(h.getListDate());
        // 1=正常交易；退市已在上层过滤。无独立 status 历史时默认在市。
        s.setStatus(1);
        return s;
    }

    private static String key(String code, String exchange) {
        return code + "|" + exchange;
    }
}
