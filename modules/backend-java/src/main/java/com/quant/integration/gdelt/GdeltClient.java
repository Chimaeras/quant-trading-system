package com.quant.integration.gdelt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GDELT 2.1 轻量客户端（免费公开源）。
 *
 * 说明：
 * - Events API:  https://api.gdeltproject.org/api/v2/events/search
 * - Docs API:    https://api.gdeltproject.org/api/v2/doc/doc
 *
 * 字段命名在不同文档/版本中可能出现大小写差异，因此解析时做了容错（GoldsteinScale vs goldsteinscale 等）。
 */
@Slf4j
@Component
public class GdeltClient {
    private static final String BASE = "https://api.gdeltproject.org/api/v2";
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RestTemplate restTemplate = createRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** GDELT 公共源限频：官方提示 1 次 / 5 秒。这里用全进程节流避免 429。 */
    private static final long MIN_INTERVAL_MS = 5200L;
    private static final AtomicLong LAST_REQUEST_AT = new AtomicLong(0L);

    /** events/search 在当前节点持续 404：避免每次请求都打一次 404，做冷却重试。 */
    private static final AtomicLong NEXT_EVENTS_RETRY_AT = new AtomicLong(0L);

    /** URL 级短缓存，减少重复请求（尤其是用户反复点运行时）。 */
    private static final long CACHE_TTL_MS = 30_000L;
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    public List<GdeltEvent> fetchEvents(
            String query,
            LocalDate startDate,
            LocalDate endDate,
            int maxRecords
    ) {
        long now = System.currentTimeMillis();
        if (now < NEXT_EVENTS_RETRY_AT.get()) {
            return List.of();
        }
        String q = (query == null || query.isBlank()) ? "CHN" : query.trim();
        LocalDate s = startDate != null ? startDate : LocalDate.now().minusDays(7);
        LocalDate e = endDate != null ? endDate : LocalDate.now();

        // 用 UTC 拼接起止时间，避免时区差导致窗口偏移
        LocalDateTime start = s.atStartOfDay();
        LocalDateTime end = e.plusDays(1).atStartOfDay().minusSeconds(1);
        // 重要：不要给 Events API 传 timeline 类 mode（在部分节点/镜像下会直接 404）。
        // 用最保守的查询参数组合，保证能拿到 events[]。
        String url = BASE + "/events/search"
                + "?query=" + enc(q)
                + "&format=json"
                + "&sort=datedesc"
                + "&startdatetime=" + enc(DT.format(start))
                + "&enddatetime=" + enc(DT.format(end))
                + "&maxrecords=" + Math.min(Math.max(1, maxRecords), 250);

        try {
            String json = getJsonWithRetry(url, true);
            if (json == null || json.isBlank()) {
                log.info("GDELT events 空响应: query={} [{}..{}]", q, s, e);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode events = root.path("events");
            if (!events.isArray()) {
                // 有些 mode 返回 timeline 结构；MVP 强制要求 events 数组，不满足则返回空
                log.info("GDELT events 缺少 events 数组: query={} [{}..{}] keys={}", q, s, e, root.fieldNames().hasNext() ? "present" : "none");
                return List.of();
            }
            List<GdeltEvent> out = new ArrayList<>();
            for (JsonNode n : events) {
                GdeltEvent ev = parseEvent(n);
                if (ev != null) out.add(ev);
            }
            log.info("GDELT events 拉取完成: query={} [{}..{}] maxRecords={} got={}", q, s, e, Math.min(Math.max(1, maxRecords), 250), out.size());
            return out;
        } catch (NotFound404 nf) {
            // 当前节点 events/search 直接 404：冷却 1 小时再尝试
            NEXT_EVENTS_RETRY_AT.set(System.currentTimeMillis() + 3600_000L);
            log.info("GDELT events 拉取失败: query={} [{}..{}] url={} - {}", q, s, e, url, nf.getMessage());
            return List.of();
        } catch (Exception ex) {
            log.info("GDELT events 拉取失败: query={} [{}..{}] url={} - {}", q, s, e, url, ex.getMessage());
            return List.of();
        }
    }

    public List<GdeltArticle> fetchArticles(
            String query,
            LocalDate startDate,
            LocalDate endDate,
            int maxRecords
    ) {
        String q = (query == null || query.isBlank()) ? "CHN" : query.trim();
        LocalDate s = startDate != null ? startDate : LocalDate.now().minusDays(3);
        LocalDate e = endDate != null ? endDate : LocalDate.now();

        LocalDateTime start = s.atStartOfDay();
        LocalDateTime end = e.plusDays(1).atStartOfDay().minusSeconds(1);
        String url = BASE + "/doc/doc"
                + "?query=" + enc(q)
                + "&format=json"
                + "&mode=ArtList"
                + "&sort=datedesc"
                + "&startdatetime=" + enc(DT.format(start))
                + "&enddatetime=" + enc(DT.format(end))
                + "&maxrecords=" + Math.min(Math.max(1, maxRecords), 250);

        try {
            String json = getJsonWithRetry(url, false);
            if (json == null || json.isBlank()) {
                log.info("GDELT articles 空响应: query={} [{}..{}]", q, s, e);
                return List.of();
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode arts = root.path("articles");
            if (!arts.isArray()) {
                log.info("GDELT articles 缺少 articles 数组: query={} [{}..{}]", q, s, e);
                return List.of();
            }
            List<GdeltArticle> out = new ArrayList<>();
            for (JsonNode n : arts) {
                GdeltArticle a = parseArticle(n);
                if (a != null) out.add(a);
            }
            log.info("GDELT articles 拉取完成: query={} [{}..{}] maxRecords={} got={}", q, s, e, Math.min(Math.max(1, maxRecords), 250), out.size());
            return out;
        } catch (Exception ex) {
            log.info("GDELT articles 拉取失败: query={} [{}..{}] - {}", q, s, e, ex.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> healthSample(String query) {
        // 用于调试：快速看看字段是否命中
        List<GdeltEvent> ev = fetchEvents(query, LocalDate.now().minusDays(2), LocalDate.now(), 50);
        List<GdeltArticle> ar = fetchArticles(query, LocalDate.now().minusDays(2), LocalDate.now(), 50);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", ev.size());
        out.put("articles", ar.size());
        if (!ev.isEmpty()) out.put("event_sample", ev.get(0).toMap());
        if (!ar.isEmpty()) out.put("article_sample", ar.get(0).toMap());
        return out;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(15));
        f.setReadTimeout(Duration.ofSeconds(90));
        return new RestTemplate(f);
    }

    private String getJsonWithRetry(String url, boolean treat404AsNotFound) throws Exception {
        CacheEntry ce = CACHE.get(url);
        long now = System.currentTimeMillis();
        if (ce != null && (now - ce.tsMs) <= CACHE_TTL_MS) {
            return ce.json;
        }

        int attempts = 0;
        while (attempts++ < 3) {
            throttle();
            try {
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
                String json = resp.getBody();
                CACHE.put(url, new CacheEntry(System.currentTimeMillis(), json));
                return json;
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                long backoff = 5500L * attempts;
                log.info("GDELT 429 TooManyRequests，等待 {}ms 后重试: {}", backoff, url);
                Thread.sleep(backoff);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                if (treat404AsNotFound) {
                    throw new NotFound404(e.getMessage());
                }
                throw e;
            }
        }

        throttle();
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        String json = resp.getBody();
        CACHE.put(url, new CacheEntry(System.currentTimeMillis(), json));
        return json;
    }

    private static void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long last = LAST_REQUEST_AT.get();
        long wait = MIN_INTERVAL_MS - (now - last);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        LAST_REQUEST_AT.set(System.currentTimeMillis());
    }

    private record CacheEntry(long tsMs, String json) {}

    private static final class NotFound404 extends Exception {
        NotFound404(String msg) { super(msg); }
    }

    private static HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        h.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        return h;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String getStr(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.path(k);
            if (!v.isMissingNode() && !v.isNull()) {
                String s = v.asText("");
                if (s != null && !s.isBlank()) return s;
            }
        }
        return null;
    }

    private static Double getDouble(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.path(k);
            if (v.isMissingNode() || v.isNull()) continue;
            if (v.isNumber()) return v.asDouble();
            try {
                String s = v.asText("").trim();
                if (!s.isEmpty()) return Double.parseDouble(s);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Long getLong(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.path(k);
            if (v.isMissingNode() || v.isNull()) continue;
            if (v.isNumber()) return v.asLong();
            try {
                String s = v.asText("").trim();
                if (!s.isEmpty()) return Long.parseLong(s);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Long parseSeenEpochMillis(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // 常见：
            // - 20260407090000
            // - 20260408T000000Z
            if (s.length() == 14) {
                LocalDateTime dt = LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                return dt.toInstant(ZoneOffset.UTC).toEpochMilli();
            }
            if (s.length() == 16 && s.endsWith("Z") && s.charAt(8) == 'T') {
                LocalDateTime dt = LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
                return dt.toInstant(ZoneOffset.UTC).toEpochMilli();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static GdeltEvent parseEvent(JsonNode n) {
        String id = getStr(n, "GLOBALEVENTID", "globaleventid");
        String eventCode = getStr(n, "EventCode", "eventcode");
        Double goldstein = getDouble(n, "GoldsteinScale", "goldsteinscale");
        Double tone = getDouble(n, "AvgTone", "avgtone");
        Long sqlDate = getLong(n, "SQLDATE", "sqldate");

        if (eventCode == null && goldstein == null && tone == null) {
            return null;
        }
        return new GdeltEvent(
                id,
                sqlDate,
                eventCode,
                goldstein != null ? goldstein : 0.0,
                tone != null ? tone : 0.0,
                getStr(n, "Actor1CountryCode", "actor1countrycode"),
                getStr(n, "Actor2CountryCode", "actor2countrycode"),
                getStr(n, "SOURCEURL", "sourceurl")
        );
    }

    private static GdeltArticle parseArticle(JsonNode n) {
        String title = getStr(n, "title", "Title");
        String url = getStr(n, "url", "URL");
        String seen = getStr(n, "seendate", "seenDate", "SeenDate");
        Double tone = getDouble(n, "tone", "Tone", "avgtone", "AvgTone");
        String domain = getStr(n, "domain", "Domain");
        String lang = getStr(n, "language", "lang", "Language");
        String sourceCountry = getStr(n, "sourceCountry", "sourcecountry", "SourceCountry");
        if (title == null && url == null) return null;
        return new GdeltArticle(title, url, seen, parseSeenEpochMillis(seen), tone != null ? tone : 0.0, domain, lang, sourceCountry);
    }
}

