package com.quant.service;

import com.quant.entity.ScreenerTemplate;
import com.quant.entity.StockInfo;
import com.quant.repository.ScreenerTemplateRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 条件选股器：基于 stock_info 的规则筛选（行业/交易所/上市时长/关键字）。
 *
 * <p>口径：
 * <ul>
 *   <li>MVP 不含实时行情过滤（需批量查最新行情，后续扩展）。</li>
 *   <li>上市时长：list_date 到今天（或指定参考日）的自然日数；list_date 缺失时排除。</li>
 *   <li>仅作筛选，不打分、不给出买卖建议。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenerService {

    private final StockInfoRepository stockInfoRepository;
    private final ScreenerTemplateRepository screenerTemplateRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> screen(
            String keyword,
            String exchange,
            String industry,
            Integer minListingDays,
            LocalDate referenceDate,
            int page,
            int size) {

        LocalDate ref = referenceDate != null ? referenceDate : LocalDate.now();
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(10, size));
        Pageable pageable = PageRequest.of(p - 1, s);

        Page<StockInfo> raw = stockInfoRepository.search(keyword, exchange, pageable);

        // 在内存中按 industry 与 minListingDays 过滤（search 未覆盖这两个条件）
        List<Map<String, Object>> filtered = raw.getContent().stream()
                .filter(info -> industry == null || industry.isBlank()
                        || industry.equalsIgnoreCase(info.getIndustry()))
                .filter(info -> {
                    if (minListingDays == null || minListingDays <= 0) return true;
                    if (info.getListDate() == null) return false;
                    long days = ChronoUnit.DAYS.between(info.getListDate(), ref);
                    return days >= minListingDays;
                })
                .map(this::toMap)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference_date", ref.toString());
        out.put("page", p);
        out.put("size", s);
        out.put("total_before_filter", raw.getTotalElements());
        out.put("returned", filtered.size());
        out.put("items", filtered);
        out.put("filters", Map.of(
                "keyword", keyword != null ? keyword : "",
                "exchange", exchange != null ? exchange : "",
                "industry", industry != null ? industry : "",
                "min_listing_days", minListingDays != null ? minListingDays : 0));
        out.put("note", "MVP 条件选股器，仅基于 stock_info 筛选；实时行情过滤为后续扩展");
        return out;
    }

    private Map<String, Object> toMap(StockInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stock_code", info.getStockCode());
        m.put("exchange_type", info.getExchangeType());
        m.put("stock_name", info.getStockName());
        m.put("industry", info.getIndustry());
        m.put("list_date", info.getListDate() != null ? info.getListDate().toString() : "");
        if (info.getListDate() != null) {
            m.put("listing_days", ChronoUnit.DAYS.between(info.getListDate(), LocalDate.now()));
        }
        return m;
    }

    @Transactional
    public Map<String, Object> saveTemplate(Long userId, String name, String keyword,
                                            String exchange, String industry, Integer minListingDays) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        ScreenerTemplate tpl = screenerTemplateRepository
                .findByUserIdAndName(userId, name.trim())
                .orElseGet(() -> {
                    ScreenerTemplate t = new ScreenerTemplate();
                    t.setUserId(userId);
                    t.setName(name.trim());
                    return t;
                });
        tpl.setKeyword(keyword);
        tpl.setExchange(exchange);
        tpl.setIndustry(industry);
        tpl.setMinListingDays(minListingDays);
        screenerTemplateRepository.save(tpl);
        log.info("选股模板保存 userId={} name={}", userId, name);
        return templateToMap(tpl);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTemplates(Long userId) {
        return screenerTemplateRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::templateToMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> deleteTemplate(Long userId, Long id) {
        long deleted = screenerTemplateRepository.deleteByUserIdAndId(userId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("deleted", deleted > 0);
        return out;
    }

    private Map<String, Object> templateToMap(ScreenerTemplate tpl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tpl.getId());
        m.put("user_id", tpl.getUserId());
        m.put("name", tpl.getName());
        m.put("keyword", tpl.getKeyword());
        m.put("exchange", tpl.getExchange());
        m.put("industry", tpl.getIndustry());
        m.put("min_listing_days", tpl.getMinListingDays());
        m.put("created_at", tpl.getCreatedAt() != null ? tpl.getCreatedAt().toString() : "");
        return m;
    }
}