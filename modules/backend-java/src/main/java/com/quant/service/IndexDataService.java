package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.IndexQuote;
import com.quant.repository.IndexQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 指数行情拉取服务（QP-005）。
 *
 * <p>接入沪深 300、中证 500、中证 1000、上证指数（东方财富指数日 K，secid 前缀 1.），
 * 供市场环境分析与基准对比使用。source=eastmoney、reliability=limited。
 *
 * <p>幂等：同 (index_code, trade_date) 已存在跳过，不覆盖旧记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexDataService {

    public static final String SOURCE_EASTMONEY = "eastmoney";
    public static final String SOURCE_TUSHARE = "tushare";
    public static final String RELIABILITY_LIMITED = "limited";
    public static final String RELIABILITY_OFFICIAL = "official";

    /** 4 指数 secid（东方财富前缀 1.）+ 代码 + 名称。 */
    public static final String[][] SUPPORTED_INDICES = {
            {"1.000300", "000300", "沪深300"},
            {"1.000905", "000905", "中证500"},
            {"1.000852", "000852", "中证1000"},
            {"1.000001", "000001", "上证指数"}
    };

    private static final String EASTMONEY_KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String FIELDS2 = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61";
    private static final int MAX_ATTEMPTS = 4;
    private static final int CHUNK_MAX_DAYS = 120;

    private final IndexQuoteRepository indexQuoteRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(20));
        f.setReadTimeout(Duration.ofSeconds(120));
        return new RestTemplate(f);
    }

    /**
     * 拉取单个指数区间日 K 并持久化（幂等：已存在跳过）。
     *
     * @param secId     东方财富 secid（如 1.000300）。
     * @param indexCode 指数代码（如 000300）。
     * @param indexName 指数名称。
     * @return inserted / skipped / total_fetched 计数。
     */
    @Transactional
    public Map<String, Object> fetchAndPersist(String secId, String indexCode, String indexName,
                                               LocalDate startDate, LocalDate endDate) {
        List<IndexQuote> fetched = fetchIndexKline(secId, indexCode, indexName, startDate, endDate);

        // 批量查询已存在的交易日，避免逐行 findByIndexCodeAndTradeDate 的 N+1 查询
        Set<LocalDate> existingDates = fetched.isEmpty() ? Set.of()
                : indexQuoteRepository.findExistingTradeDates(indexCode,
                        fetched.stream().map(IndexQuote::getTradeDate).collect(java.util.stream.Collectors.toList()));

        int inserted = 0;
        int skipped = 0;
        List<IndexQuote> toSave = new ArrayList<>();
        for (IndexQuote q : fetched) {
            if (existingDates.contains(q.getTradeDate())) {
                skipped++;
                continue;
            }
            toSave.add(q);
            inserted++;
        }
        if (!toSave.isEmpty()) {
            indexQuoteRepository.saveAll(toSave);
        }
        log.info("指数行情拉取 {} [{}..{}]: 拉取 {} 条, 新增 {} 条, 跳过已存在 {} 条",
                indexCode, startDate, endDate, fetched.size(), inserted, skipped);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index_code", indexCode);
        out.put("index_name", indexName);
        out.put("total_fetched", fetched.size());
        out.put("inserted", inserted);
        out.put("skipped", skipped);
        out.put("source", SOURCE_EASTMONEY);
        out.put("reliability", RELIABILITY_LIMITED);
        return out;
    }

    private List<IndexQuote> fetchIndexKline(String secId, String indexCode, String indexName,
                                             LocalDate startDate, LocalDate endDate) {
        List<IndexQuote> all = new ArrayList<>();
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days <= CHUNK_MAX_DAYS) {
            all.addAll(fetchIndexOnce(secId, indexCode, indexName, startDate, endDate));
            return all;
        }
        LocalDate cur = startDate;
        while (!cur.isAfter(endDate)) {
            LocalDate chunkEnd = cur.plusMonths(1).minusDays(1);
            if (chunkEnd.isAfter(endDate)) {
                chunkEnd = endDate;
            }
            all.addAll(fetchIndexOnce(secId, indexCode, indexName, cur, chunkEnd));
            cur = chunkEnd.plusDays(1);
        }
        return all;
    }

    private List<IndexQuote> fetchIndexOnce(String secId, String indexCode, String indexName,
                                            LocalDate startDate, LocalDate endDate) {
        String beg = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = EASTMONEY_KLINE_URL + "?secid=" + secId
                + "&fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=" + FIELDS2
                + "&klt=101&fqt=1"
                + "&beg=" + beg + "&end=" + end;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpHeaders headers = eastmoneyHeaders();
                ResponseEntity<String> resp = restTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                String json = resp.getBody();
                if (json == null || json.isBlank()) {
                    return List.of();
                }
                return parseIndexKlines(indexCode, indexName, json);
            } catch (Exception e) {
                boolean last = attempt >= MAX_ATTEMPTS;
                if (last) {
                    log.warn("指数行情拉取失败 {} [{}..{}]: {}", indexCode, beg, end, e.getMessage());
                    return List.of();
                }
                long backoffMs = 300L * (1L << (attempt - 1));
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
        }
        return List.of();
    }

    /** 解析指数日 K：f51日期 f52开 f53收 f54高 f55低 f56成交量 f57成交额 f58振幅 f59涨跌幅 f60涨跌额 f61换手率。 */
    List<IndexQuote> parseIndexKlines(String indexCode, String indexName, String json) throws Exception {
        List<IndexQuote> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.path("data");
        if (data.isNull() || !data.isObject()) {
            return result;
        }
        JsonNode klines = data.path("klines");
        if (!klines.isArray()) {
            return result;
        }
        for (JsonNode k : klines) {
            String line = k.asText("");
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 7) {
                continue;
            }
            LocalDate tradeDate;
            try {
                tradeDate = LocalDate.parse(parts[0]);
            } catch (Exception ignore) {
                continue;
            }
            IndexQuote q = new IndexQuote();
            q.setIndexCode(indexCode);
            q.setIndexName(indexName);
            q.setTradeDate(tradeDate);
            q.setOpenPrice(toBd(parts, 1));
            q.setClosePrice(toBd(parts, 2));
            q.setHighPrice(toBd(parts, 3));
            q.setLowPrice(toBd(parts, 4));
            q.setVolume(toLong(parts, 5));
            q.setAmount(toBd(parts, 6));
            if (parts.length > 8) {
                q.setChangePct(toBd(parts, 8));
            }
            q.setSource(SOURCE_EASTMONEY);
            q.setReliability(RELIABILITY_LIMITED);
            result.add(q);
        }
        return result;
    }

    private static BigDecimal toBd(String[] arr, int i) {
        if (i >= arr.length || arr[i] == null || arr[i].isBlank() || "-".equals(arr[i])) {
            return null;
        }
        try {
            return new BigDecimal(arr[i].trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long toLong(String[] arr, int i) {
        if (i >= arr.length || arr[i] == null || arr[i].isBlank() || "-".equals(arr[i])) {
            return null;
        }
        try {
            return Long.parseLong(arr[i].trim());
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(arr[i].trim());
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private static HttpHeaders eastmoneyHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        h.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        h.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8");
        h.set(HttpHeaders.REFERER, "https://quote.eastmoney.com/");
        h.set("Origin", "https://quote.eastmoney.com");
        return h;
    }

    /** 覆盖率统计（供数据质量页）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> buildCoverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = indexQuoteRepository.count();
        out.put("total_rows", total);
        out.put("distinct_indices", indexQuoteRepository.countDistinctIndices());

        List<Map<String, Object>> indices = new ArrayList<>();
        for (String[] meta : SUPPORTED_INDICES) {
            String code = meta[1];
            String name = meta[2];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index_code", code);
            m.put("index_name", name);
            m.put("rows", indexQuoteRepository.countByIndex(code));
            Optional<LocalDate> min = indexQuoteRepository.findMinTradeDateByIndex(code);
            Optional<LocalDate> max = indexQuoteRepository.findMaxTradeDateByIndex(code);
            m.put("earliest_date", min.map(LocalDate::toString).orElse(""));
            m.put("latest_date", max.map(LocalDate::toString).orElse(""));
            indices.add(m);
        }
        out.put("indices", indices);
        out.put("by_source", Map.of(
                SOURCE_EASTMONEY, indexQuoteRepository.countBySource(SOURCE_EASTMONEY),
                SOURCE_TUSHARE, indexQuoteRepository.countBySource(SOURCE_TUSHARE)));
        out.put("by_reliability", Map.of(
                RELIABILITY_LIMITED, indexQuoteRepository.countByReliability(RELIABILITY_LIMITED),
                RELIABILITY_OFFICIAL, indexQuoteRepository.countByReliability(RELIABILITY_OFFICIAL)));
        out.put("status", total > 0 ? "已启用（东方财富指数日K，可靠度 limited）" : "未拉取（可通过 /api/data/index/fetch 拉取）");
        out.put("note", "接入沪深300/中证500/中证1000/上证指数，供市场环境分析与基准对比；"
                + "后续接入 Tushare 指数接口可替换为 official");
        return out;
    }
}
