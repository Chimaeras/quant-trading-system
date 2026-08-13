package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.TradingCalendarDay;
import com.quant.repository.TradingCalendarDayRepository;
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
import java.util.TreeSet;

/**
 * 真实交易日历服务（QP-005）。
 *
 * <p>用上证指数（东方财富 secid=1.000001）日 K 的 trade_date 集合反推真实交易日，
 * 覆盖法定节假日与临时休市，不再用「自然日排除周末」近似。
 * source=eastmoney_derived、reliability=limited：非官方交易日历表，极端情况（指数缺日）可能漏判；
 * 后续接入 Tushare trade_cal 官方日历后替换 source=tushare、reliability=official。
 *
 * <p>幂等：同 trade_date 已存在则跳过，不覆盖旧记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingCalendarService {

    public static final String SOURCE_EASTMONEY_DERIVED = "eastmoney_derived";
    public static final String SOURCE_TUSHARE = "tushare";
    public static final String RELIABILITY_LIMITED = "limited";
    public static final String RELIABILITY_OFFICIAL = "official";

    /** 上证指数 secid（1.000001）。 */
    private static final String SH_INDEX_SECID = "1.000001";
    private static final String EASTMONEY_KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String FIELDS2 = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61";
    private static final int MAX_ATTEMPTS = 4;
    private static final int CHUNK_MAX_DAYS = 120;

    private final TradingCalendarDayRepository tradingCalendarDayRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(20));
        f.setReadTimeout(Duration.ofSeconds(120));
        return new RestTemplate(f);
    }

    /**
     * 从上证指数日 K 反推交易日并持久化（区间内分片拉取）。
     * 幂等：同 trade_date 已存在跳过。
     *
     * @return inserted / skipped_existing / total_fetched / source / reliability 计数。
     */
    @Transactional
    public Map<String, Object> deriveFromShIndex(LocalDate startDate, LocalDate endDate) {
        Set<LocalDate> fetched = fetchShIndexTradeDates(startDate, endDate);

        // 批量查询已存在的交易日，避免逐行 findByTradeDate 的 N+1 查询
        Set<LocalDate> existing = new HashSet<>(
                tradingCalendarDayRepository.findTradeDatesBetween(startDate, endDate));

        int inserted = 0;
        int skippedExisting = 0;
        List<TradingCalendarDay> toSave = new ArrayList<>();
        for (LocalDate d : fetched) {
            if (existing.contains(d)) {
                skippedExisting++;
                continue;
            }
            TradingCalendarDay day = new TradingCalendarDay();
            day.setTradeDate(d);
            day.setIsTradingDay(true);
            day.setSource(SOURCE_EASTMONEY_DERIVED);
            day.setReliability(RELIABILITY_LIMITED);
            toSave.add(day);
            inserted++;
        }
        if (!toSave.isEmpty()) {
            tradingCalendarDayRepository.saveAll(toSave);
        }
        log.info("交易日历反推 [{}..{}]: 拉取 {} 个交易日, 新增 {} 条, 跳过已存在 {} 条",
                startDate, endDate, fetched.size(), inserted, skippedExisting);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_fetched", fetched.size());
        out.put("inserted", inserted);
        out.put("skipped_existing", skippedExisting);
        out.put("source", SOURCE_EASTMONEY_DERIVED);
        out.put("reliability", RELIABILITY_LIMITED);
        out.put("note", "由上证指数日K反推真实交易日，覆盖法定节假日与临时休市；"
                + "非官方日历表，极端情况指数缺日可能漏判，后续接入 Tushare trade_cal 替换");
        return out;
    }

    /** 拉取上证指数区间内全部交易日（长区间按月分片，防 EOF）。 */
    private Set<LocalDate> fetchShIndexTradeDates(LocalDate startDate, LocalDate endDate) {
        Set<LocalDate> all = new TreeSet<>();
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days <= CHUNK_MAX_DAYS) {
            all.addAll(fetchShIndexOnce(startDate, endDate));
            return all;
        }
        LocalDate cur = startDate;
        while (!cur.isAfter(endDate)) {
            LocalDate chunkEnd = cur.plusMonths(1).minusDays(1);
            if (chunkEnd.isAfter(endDate)) {
                chunkEnd = endDate;
            }
            all.addAll(fetchShIndexOnce(cur, chunkEnd));
            cur = chunkEnd.plusDays(1);
        }
        return all;
    }

    private List<LocalDate> fetchShIndexOnce(LocalDate startDate, LocalDate endDate) {
        String beg = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = EASTMONEY_KLINE_URL + "?secid=" + SH_INDEX_SECID
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
                return parseTradeDates(json);
            } catch (Exception e) {
                boolean last = attempt >= MAX_ATTEMPTS;
                if (last) {
                    log.warn("上证指数交易日历反推失败 [{}..{}]: {}", beg, end, e.getMessage());
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

    /** 解析东方财富 K 线 JSON，仅提取 trade_date（f51，格式 YYYY-MM-DD）。 */
    List<LocalDate> parseTradeDates(String json) throws Exception {
        List<LocalDate> result = new ArrayList<>();
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
            String datePart = line.split(",")[0];
            try {
                result.add(LocalDate.parse(datePart));
            } catch (Exception ignore) {
                // 跳过无法解析的行
            }
        }
        return result;
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

    /** 判断某日是否为交易日（日历表存在该日即为交易日）。 */
    @Transactional(readOnly = true)
    public boolean isTradingDay(LocalDate date) {
        return tradingCalendarDayRepository.findByTradeDate(date).isPresent();
    }

    /** 区间内全部交易日（升序）。 */
    @Transactional(readOnly = true)
    public List<LocalDate> findTradingDays(LocalDate startDate, LocalDate endDate) {
        return tradingCalendarDayRepository.findTradeDatesBetween(startDate, endDate);
    }

    /**
     * 严格晚于 {@code after} 的下一个市场交易日。
     * 缓冲窗口：after+1 .. after+60。
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> nextTradingDay(LocalDate after) {
        if (after == null) {
            return Optional.empty();
        }
        List<LocalDate> days = findTradingDays(after.plusDays(1), after.plusDays(60));
        return days.isEmpty() ? Optional.empty() : Optional.of(days.get(0));
    }

    /**
     * {@code fromExclusive} 之后的第 n 个市场交易日（不含 fromExclusive 当日）。
     * 持有 horizon 天 → 退出日 = entry 后第 horizon 个交易日。
     * 缓冲窗口：from+1 .. from+max(60, n*3+10)。
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> tradingDayAfterN(LocalDate fromExclusive, int n) {
        if (fromExclusive == null || n <= 0) {
            return Optional.empty();
        }
        int bufferDays = Math.max(60, n * 3 + 10);
        List<LocalDate> days = findTradingDays(fromExclusive.plusDays(1), fromExclusive.plusDays(bufferDays));
        if (days.size() < n) {
            return Optional.empty();
        }
        return Optional.of(days.get(n - 1));
    }

    /** 覆盖率统计（供数据质量页）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> buildCoverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = tradingCalendarDayRepository.count();
        out.put("total_rows", total);
        out.put("by_source", Map.of(
                SOURCE_EASTMONEY_DERIVED, tradingCalendarDayRepository.countBySource(SOURCE_EASTMONEY_DERIVED),
                SOURCE_TUSHARE, tradingCalendarDayRepository.countBySource(SOURCE_TUSHARE)));
        out.put("by_reliability", Map.of(
                RELIABILITY_LIMITED, tradingCalendarDayRepository.countByReliability(RELIABILITY_LIMITED),
                RELIABILITY_OFFICIAL, tradingCalendarDayRepository.countByReliability(RELIABILITY_OFFICIAL)));
        Optional<LocalDate> min = tradingCalendarDayRepository.findMinTradeDate();
        Optional<LocalDate> max = tradingCalendarDayRepository.findMaxTradeDate();
        out.put("earliest_date", min.map(LocalDate::toString).orElse(""));
        out.put("latest_date", max.map(LocalDate::toString).orElse(""));
        out.put("status", total > 0 ? "已启用（上证指数反推，可靠度 limited）" : "未初始化（可通过 /api/data/trading-calendar/init 初始化）");
        out.put("note", "由上证指数日K trade_date 反推真实交易日，覆盖法定节假日与临时休市；"
                + "非官方日历表，后续接入 Tushare trade_cal 替换为 official");
        return out;
    }
}
