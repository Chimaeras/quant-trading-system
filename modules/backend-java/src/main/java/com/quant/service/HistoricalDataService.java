package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.AdjustFactor;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.research.AdjustFactorDerivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 历史行情服务 - 主数据源：搜狐 hisHq（quant-core 同源 URL，通常更快）；
 * 备用：东方财富日 K（搜狐无数据或配置开启补全时拉取）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalDataService {

    /**
     * 与 StockInfo {@code UpdateUtil.insertOrUpdateStockInfoCurrDate} 同风格日志（与 {@link #log} 混合策略区分）。
     */
    private static final Logger STOCKINFO_MAINBOARD = LoggerFactory.getLogger("com.quant.stockinfo.MainBoard");

    /** 连接/读超时 + 分片请求，减轻「Unexpected end of file from server」类 EOF */
    private final RestTemplate restTemplate = createRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MarketDataRepository marketDataRepo;
    private final MarketPriceRawRepository marketPriceRawRepo;
    private final AdjustFactorRepository adjustFactorRepo;
    private final AdjustFactorDerivationService adjustFactorDerivationService;

    /** 为 true 时：搜狐已有数据仍拉东财并合并（搜狐优先，东财仅补缺交易日） */
    @Value("${historical.supplement-eastmoney:false}")
    private boolean supplementEastMoney;

    private static final String EASTMONEY_KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    /** 与 quant-core UpdateUtil 一致：搜狐历史行情 */
    private static final String SOHU_HIS_HQ_URL = "https://q.stock.sohu.com/hisHq";
    /** 东财单次跨度上限（按月分片，防 EOF） */
    private static final int EASTMONEY_CHUNK_MAX_DAYS = 120;
    /** 搜狐单次可拉更长区间，减少 HTTP 往返（quant-core 常一次拉整段） */
    private static final int SOHU_CHUNK_MAX_DAYS = 500;
    private static final int MAX_ATTEMPTS = 4;
    private static final int INSERT_BATCH_SIZE = 500;
    /** fields2: f51日期 f52开 f53收 f54高 f55低 f56成交量 f57成交额 f58振幅 f59涨跌幅 f60涨跌额 f61换手率 */
    private static final String FIELDS2 = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61";

    /**
     * 更新指定股票在日期范围内的历史行情，仅补充缺失的交易日
     *
     * @param stockCode   股票代码，如 000001
     * @param exchangeType 交易所 SSE/SZSE/BSE
     * @param startDate   开始日期
     * @param endDate     结束日期
     * @return inserted 新插入条数, skipped 已存在跳过条数, total 接口返回总条数
     */
    @Transactional
    public Map<String, Object> updateHistoryInRange(String stockCode, String exchangeType,
                                                     LocalDate startDate, LocalDate endDate) {
        String code = (stockCode != null ? stockCode : "").split("\\.")[0].trim();
        if (code.isEmpty()) {
            return Map.of("inserted", 0, "skipped", 0, "total", 0, "message", "股票代码不能为空");
        }
        String ex = (exchangeType != null && !exchangeType.isBlank()) ? exchangeType : inferExchangeType(code);
        if (startDate == null) startDate = LocalDate.now().minusYears(1);
        if (endDate == null) endDate = LocalDate.now();
        if (startDate.isAfter(endDate)) {
            LocalDate t = startDate;
            startDate = endDate;
            endDate = t;
        }

        List<MarketData> sohu = fetchFromSohuChunked(code, ex, startDate, endDate);
        List<MarketData> em = List.of();

        List<MarketData> fetched;
        String dataSource;
        int eastmoneySupplementDays = 0;

        if (!sohu.isEmpty() && supplementEastMoney) {
            em = fetchFromEastmoneyChunked(code, ex, startDate, endDate);
            Set<LocalDate> sohuDates = sohu.stream().map(MarketData::getTradeDate).collect(Collectors.toSet());
            eastmoneySupplementDays = (int) em.stream().map(MarketData::getTradeDate).filter(d -> !sohuDates.contains(d)).count();
            fetched = mergePreferSohu(sohu, em);
            dataSource = eastmoneySupplementDays > 0 ? "sohu+eastmoney" : "sohu";
            if (eastmoneySupplementDays > 0) {
                log.debug("[hybrid] 搜狐为主，东方财富补全 {} 个交易日: {} {}", eastmoneySupplementDays, code, ex);
            }
        } else if (!sohu.isEmpty()) {
            fetched = sohu;
            dataSource = "sohu";
        } else {
            String beg = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
            String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
            log.info("[hybrid] 搜狐无数据，尝试东方财富备用: {} {} [{}..{}] primarySOHU={} backupEM={}",
                    code, ex, startDate, endDate,
                    SOHU_HIS_HQ_URL + "?code=cn_" + code + "&start=" + beg + "&end=" + end,
                    EASTMONEY_KLINE_URL + "?secid=" + toEastmoneySecId(code, ex)
                            + "&fields1=f1,f2,f3,f4,f5,f6&fields2=" + FIELDS2 + "&klt=101&fqt=1&beg=" + beg + "&end=" + end);
            em = fetchFromEastmoneyChunked(code, ex, startDate, endDate);
            fetched = em;
            dataSource = em.isEmpty() ? "none" : "eastmoney";
        }

        if (fetched.isEmpty()) {
            return Map.of("inserted", 0, "skipped", 0, "total", 0, "message", "未获取到数据，请检查股票代码或网络",
                    "data_source", "none");
        }

        Set<LocalDate> existingDates = new HashSet<>(
                marketDataRepo.findExistingTradeDates(code, ex, startDate, endDate));
        List<MarketData> toInsert = fetched.stream()
                .filter(m -> !existingDates.contains(m.getTradeDate()))
                .toList();
        saveInBatches(toInsert);

        String msg = String.format("新增 %d 条，跳过已存在 %d 条", toInsert.size(), fetched.size() - toInsert.size());
        if ("eastmoney".equals(dataSource)) {
            msg = msg + "（数据源：东方财富备用）";
        } else if ("sohu+eastmoney".equals(dataSource)) {
            msg = msg + String.format("（搜狐为主，东方财富补全 %d 个交易日）", eastmoneySupplementDays);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", toInsert.size());
        out.put("skipped", fetched.size() - toInsert.size());
        out.put("total", fetched.size());
        out.put("message", msg);
        out.put("data_source", dataSource);
        if (eastmoneySupplementDays > 0) {
            out.put("eastmoney_supplement_days", eastmoneySupplementDays);
        }
        return out;
    }

    /**
     * 主板枚举任务专用：与 StockInfo {@code UpdateUtil.insertOrUpdateStockInfoCurrDate} 同源逻辑——
     * 仅单次 GET {@code https://q.stock.sohu.com/hisHq?code=cn_xxx&start=yyyyMMdd&end=yyyyMMdd}（不分片、不重试、无东方财富），
     * 日志使用 {@link #STOCKINFO_MAINBOARD}，文案与 StockInfo 控制台输出一致。
     */
    @Transactional
    public Map<String, Object> updateHistoryInRangeStockInfoStyle(String stockCode, String exchangeType,
                                                                  LocalDate startDate, LocalDate endDate) {
        String code = (stockCode != null ? stockCode : "").split("\\.")[0].trim();
        if (code.isEmpty()) {
            return Map.of("inserted", 0, "skipped", 0, "total", 0, "message", "股票代码不能为空",
                    "data_source", "stockinfo_sohu");
        }
        String ex = (exchangeType != null && !exchangeType.isBlank()) ? exchangeType : inferExchangeType(code);
        if (startDate == null) startDate = LocalDate.now().minusYears(1);
        if (endDate == null) endDate = LocalDate.now();
        if (startDate.isAfter(endDate)) {
            LocalDate t = startDate;
            startDate = endDate;
            endDate = t;
        }

        STOCKINFO_MAINBOARD.info(code);

        String start = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = SOHU_HIS_HQ_URL + "?code=cn_" + code + "&start=" + start + "&end=" + end;

        String jsonResponse = sendGetRequestStockInfoStyle(url);
        if (jsonResponse == null) {
            STOCKINFO_MAINBOARD.info("Failed to get data for stock code: " + code);
            return Map.of("inserted", 0, "skipped", 0, "total", 0,
                    "message", "Failed to get data for stock code: " + code,
                    "data_source", "stockinfo_sohu");
        }

        List<MarketData> fetched;
        try {
            fetched = parseSohuHqJson(code, ex, jsonResponse, startDate, endDate);
        } catch (Exception e) {
            STOCKINFO_MAINBOARD.error("Error parsing JSON: " + e.getMessage());
            return Map.of("inserted", 0, "skipped", 0, "total", 0,
                    "message", "解析 JSON 失败: " + e.getMessage(),
                    "data_source", "stockinfo_sohu");
        }

        STOCKINFO_MAINBOARD.info("records:");
        STOCKINFO_MAINBOARD.info(String.valueOf(fetched));

        for (MarketData m : fetched) {
            STOCKINFO_MAINBOARD.info("INSERT INTO market_data VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                    + "date=" + m.getTradeDate() + " code=" + code + " open=" + m.getOpenPrice()
                    + " close=" + m.getClosePrice());
        }

        if (fetched.isEmpty()) {
            return Map.of("inserted", 0, "skipped", 0, "total", 0, "message", "未获取到数据",
                    "data_source", "stockinfo_sohu");
        }

        Set<LocalDate> existingDates = new HashSet<>(
                marketDataRepo.findExistingTradeDates(code, ex, startDate, endDate));
        List<MarketData> toInsert = fetched.stream()
                .filter(m -> !existingDates.contains(m.getTradeDate()))
                .toList();
        saveInBatches(toInsert);

        String msg = String.format("新增 %d 条，跳过已存在 %d 条（StockInfo 同源搜狐 hisHq）", toInsert.size(), fetched.size() - toInsert.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", toInsert.size());
        out.put("skipped", fetched.size() - toInsert.size());
        out.put("total", fetched.size());
        out.put("message", msg);
        out.put("data_source", "stockinfo_sohu");
        return out;
    }

    /**
     * 与 StockInfo {@code UpdateUtil.sendGetRequest} 一致：HttpURLConnection GET，无自定义 UA，非 200 时打印 response code。
     */
    private static String sendGetRequestStockInfoStyle(String urlString) {
        try {
            URL obj = new URL(urlString);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("GET");

            int responseCode = con.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                    String inputLine;
                    StringBuilder response = new StringBuilder();
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    return response.toString();
                }
            } else {
                STOCKINFO_MAINBOARD.info("GET request failed with response code: " + responseCode);
                return null;
            }
        } catch (Exception e) {
            log.warn("HTTP 请求失败: {}", e.getMessage());
            return null;
        }
    }

    /** 搜狐优先；同一交易日东财仅在没有搜狐该日时写入 */
    private static List<MarketData> mergePreferSohu(List<MarketData> sohu, List<MarketData> em) {
        TreeMap<LocalDate, MarketData> merged = new TreeMap<>();
        for (MarketData s : sohu) {
            merged.put(s.getTradeDate(), s);
        }
        for (MarketData e : em) {
            merged.putIfAbsent(e.getTradeDate(), e);
        }
        return new ArrayList<>(merged.values());
    }

    private void saveInBatches(List<MarketData> toInsert) {
        if (toInsert.isEmpty()) {
            return;
        }
        // 统一入库质量闸：单股增量路径不应绕过 isValidDailyBar（fetch/解析层已过滤，这里再做一道
        // 防未来新数据源遗漏），避免异常 OHLC（high<low、open/close 越界、负量额）污染选股输入。
        List<MarketData> validated = toInsert.stream()
                .filter(HistoricalDataService::isValidDailyBar)
                .toList();
        if (validated.isEmpty()) {
            return;
        }
        for (int i = 0; i < validated.size(); i += INSERT_BATCH_SIZE) {
            int end = Math.min(i + INSERT_BATCH_SIZE, validated.size());
            marketDataRepo.saveAll(validated.subList(i, end));
        }
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(20));
        f.setReadTimeout(Duration.ofSeconds(120));
        return new RestTemplate(f);
    }

    /** 东财：长区间按月分片，避免单次响应过大导致服务端提前断开（Unexpected EOF） */
    private List<MarketData> fetchFromEastmoneyChunked(String code, String exchangeType,
                                                       LocalDate startDate, LocalDate endDate) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days <= EASTMONEY_CHUNK_MAX_DAYS) {
            return fetchFromEastmoneyOnce(code, exchangeType, startDate, endDate);
        }
        Map<LocalDate, MarketData> byDate = new TreeMap<>();
        LocalDate cur = startDate;
        while (!cur.isAfter(endDate)) {
            LocalDate chunkEnd = cur.plusMonths(1).minusDays(1);
            if (chunkEnd.isAfter(endDate)) {
                chunkEnd = endDate;
            }
            List<MarketData> part = fetchFromEastmoneyOnce(code, exchangeType, cur, chunkEnd);
            for (MarketData m : part) {
                byDate.put(m.getTradeDate(), m);
            }
            cur = chunkEnd.plusDays(1);
        }
        return new ArrayList<>(byDate.values());
    }

    /**
     * 搜狐 hisHq：单次可拉更长区间，减少请求次数；超长区间仍按月分片。
     */
    private List<MarketData> fetchFromSohuChunked(String code, String exchangeType,
                                                  LocalDate startDate, LocalDate endDate) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days <= SOHU_CHUNK_MAX_DAYS) {
            return fetchFromSohuOnce(code, exchangeType, startDate, endDate);
        }
        Map<LocalDate, MarketData> byDate = new TreeMap<>();
        LocalDate cur = startDate;
        while (!cur.isAfter(endDate)) {
            LocalDate chunkEnd = cur.plusMonths(1).minusDays(1);
            if (chunkEnd.isAfter(endDate)) {
                chunkEnd = endDate;
            }
            List<MarketData> part = fetchFromSohuOnce(code, exchangeType, cur, chunkEnd);
            for (MarketData m : part) {
                byDate.put(m.getTradeDate(), m);
            }
            cur = chunkEnd.plusDays(1);
        }
        return new ArrayList<>(byDate.values());
    }

    private List<MarketData> fetchFromSohuOnce(String code, String exchangeType,
                                               LocalDate startDate, LocalDate endDate) {
        String cn = toSohuCnParam(code);
        String start = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = SOHU_HIS_HQ_URL + "?code=" + cn
                + "&start=" + start + "&end=" + end;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpHeaders headers = sohuHeaders();
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                String json = resp.getBody();
                if (json == null || json.isBlank()) {
                    return List.of();
                }
                return parseSohuHqJson(code, exchangeType, json, startDate, endDate);
            } catch (Exception e) {
                boolean last = attempt >= MAX_ATTEMPTS;
                if (last) {
                    log.warn("[hybrid] 搜狐历史行情失败: {} {} [{}..{}] - {}", code, exchangeType, start, end, e.getMessage());
                    return List.of();
                }
                long backoffMs = 300L * (1L << (attempt - 1));
                log.debug("[hybrid] 搜狐 K 线重试 {}/{} {} 等待 {}ms: {}", attempt, MAX_ATTEMPTS, code, backoffMs, e.getMessage());
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

    /** 与 quant-core 一致：cn_ + 六位代码 */
    private static String toSohuCnParam(String code) {
        String c = (code != null ? code : "").trim();
        if (c.length() > 6) {
            c = c.substring(0, 6);
        }
        return "cn_" + c;
    }

    private static HttpHeaders sohuHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        h.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        h.set(HttpHeaders.REFERER, "https://q.stock.sohu.com/");
        return h;
    }

    /**
     * 解析搜狐返回：根为数组，首元素含 hq 数组；每行 [日期,开,收,涨跌额,涨跌幅%,低,高,量,额,换手率%]
     * 与 quant-core {@link StringUtil#parseJson} 字段顺序一致。
     */
    private List<MarketData> parseSohuHqJson(String code, String exchangeType, String json,
                                             LocalDate rangeStart, LocalDate rangeEnd) throws Exception {
        List<MarketData> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray() || root.isEmpty()) {
            return result;
        }
        JsonNode hq = root.get(0).path("hq");
        if (!hq.isArray()) {
            return result;
        }
        for (JsonNode row : hq) {
            if (!row.isArray() || row.size() < 10) {
                continue;
            }
            MarketData m = parseSohuHqRow(code, exchangeType, row);
            if (!isValidDailyBar(m)) {
                continue;
            }
            if (m.getTradeDate().isBefore(rangeStart) || m.getTradeDate().isAfter(rangeEnd)) {
                continue;
            }
            result.add(m);
        }
        result.sort(Comparator.comparing(MarketData::getTradeDate));
        return dedupeByTradeDate(result);
    }

    /**
     * 同一 trade_date 保留最后一条（源接口偶发重复行会导致 uk_code_date 冲突）。
     */
    static List<MarketData> dedupeByTradeDate(List<MarketData> bars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        TreeMap<LocalDate, MarketData> byDate = new TreeMap<>();
        for (MarketData bar : bars) {
            if (bar != null && bar.getTradeDate() != null) {
                byDate.put(bar.getTradeDate(), bar);
            }
        }
        return new ArrayList<>(byDate.values());
    }

    private MarketData parseSohuHqRow(String code, String exchangeType, JsonNode data) {
        try {
            String dateStr = data.get(0).asText();
            if (dateStr.isBlank() || "-".equals(dateStr.trim())) {
                return null;
            }
            LocalDate tradeDate = parseDate(dateStr.trim());
            if (tradeDate == null) {
                return null;
            }
            double open = data.get(1).asDouble();
            double close = data.get(2).asDouble();
            double changeAmt = data.get(3).asDouble();
            String changePctStr = data.get(4).asText("");
            double low = data.get(5).asDouble();
            double high = data.get(6).asDouble();
            long volume = jsonLong(data.get(7));
            double turnover = data.get(8).asDouble();
            String turnoverRateStr = data.get(9).asText("");

            MarketData m = new MarketData();
            m.setStockCode(code);
            m.setExchangeType(exchangeType);
            m.setTradeDate(tradeDate);
            m.setOpenPrice(BigDecimal.valueOf(open));
            m.setClosePrice(BigDecimal.valueOf(close));
            m.setHighPrice(BigDecimal.valueOf(high));
            m.setLowPrice(BigDecimal.valueOf(low));
            m.setVolume(volume);
            m.setAmount(BigDecimal.valueOf(turnover));
            m.setChangePct(BigDecimal.valueOf(removePercentageSuffix(changePctStr)));
            m.setTurnoverRate(BigDecimal.valueOf(removePercentageSuffix(turnoverRateStr)));
            populateTotalMarketValue(m);
            if (m.getClosePrice() != null) {
                BigDecimal pre = m.getClosePrice().subtract(BigDecimal.valueOf(changeAmt));
                m.setPreClose(pre);
            }
            return m;
        } catch (Exception e) {
            log.debug("[hybrid] 解析搜狐单行失败: {}", e.getMessage());
            return null;
        }
    }

    private static double removePercentageSuffix(String s) {
        if (s == null || s.isBlank() || "-".equals(s.trim())) {
            return 0.0;
        }
        try {
            return Double.parseDouble(s.trim().replace("%", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static long jsonLong(JsonNode n) {
        if (n == null || n.isNull()) {
            return 0L;
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        String t = n.asText("").replace(",", "").trim();
        if (t.isEmpty() || "-".equals(t)) {
            return 0L;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return (long) Double.parseDouble(t);
        }
    }

    private List<MarketData> fetchFromEastmoneyOnce(String code, String exchangeType,
                                                    LocalDate startDate, LocalDate endDate) {
        String secid = toEastmoneySecId(code, exchangeType);
        String beg = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = EASTMONEY_KLINE_URL + "?secid=" + secid
                + "&fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=" + FIELDS2
                + "&klt=101&fqt=1"
                + "&beg=" + beg + "&end=" + end;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpHeaders headers = eastmoneyHeaders();
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                String json = resp.getBody();
                if (json == null || json.isBlank()) {
                    return List.of();
                }
                return parseKlinesJson(code, exchangeType, json);
            } catch (Exception e) {
                boolean last = attempt >= MAX_ATTEMPTS;
                if (last) {
                    log.warn("[hybrid] 东方财富历史行情失败: {} {} [{}..{}] - {}", code, exchangeType, beg, end, e.getMessage());
                    return List.of();
                }
                long backoffMs = 300L * (1L << (attempt - 1));
                log.debug("[hybrid] 东方财富 K 线请求重试 {}/{} {} {} 等待 {}ms: {}", attempt, MAX_ATTEMPTS, code, exchangeType, backoffMs, e.getMessage());
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

    private List<MarketData> parseKlinesJson(String code, String exchangeType, String json) throws Exception {
        List<MarketData> result = new ArrayList<>();
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
            MarketData m = parseKlineLine(code, exchangeType, line);
            if (m != null) {
                result.add(m);
            }
        }
        return dedupeByTradeDate(result);
    }

    /** kline 格式: 日期,开,收,高,低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率 */
    private MarketData parseKlineLine(String code, String exchangeType, String line) {
        String[] parts = line.split(",");
        if (parts.length < 7) return null;
        LocalDate tradeDate = parseDate(parts[0]);
        if (tradeDate == null) return null;

        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(exchangeType);
        m.setTradeDate(tradeDate);
        m.setOpenPrice(toBigDecimal(parts, 1));
        m.setClosePrice(toBigDecimal(parts, 2));
        m.setHighPrice(toBigDecimal(parts, 3));
        m.setLowPrice(toBigDecimal(parts, 4));
        m.setVolume(toLong(parts, 5));
        m.setAmount(toBigDecimal(parts, 6));
        if (parts.length > 8) m.setChangePct(toBigDecimal(parts, 8));
        if (parts.length > 10) m.setTurnoverRate(toBigDecimal(parts, 10));
        populateTotalMarketValue(m);
        if (m.getClosePrice() != null && m.getChangePct() != null) {
            try {
                BigDecimal pct = m.getChangePct().divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
                BigDecimal divisor = BigDecimal.ONE.add(pct);
                if (divisor.compareTo(BigDecimal.ZERO) != 0) {
                    m.setPreClose(m.getClosePrice().divide(divisor, 4, java.math.RoundingMode.HALF_UP));
                }
            } catch (Exception e) {
                log.debug("preClose 由 changePct 反推失败（close={}, changePct={}）: {}", m.getClosePrice(), m.getChangePct(), e.getMessage());
            }
        }
        return isValidDailyBar(m) ? m : null;
    }

    /**
     * 由成交额/换手率推算流通市值近似并写入实体（MP-025）。
     *
     * <p>换手率通常对应流通口径，不得标为可靠总市值。标记：
     * type=FLOAT_CAP_APPROX, source=amount_div_turnover, reliability=UNKNOWN_LEGACY。
     */
    static void populateTotalMarketValue(MarketData bar) {
        if (bar == null || bar.getAmount() == null || bar.getTurnoverRate() == null) {
            return;
        }
        double amount = bar.getAmount().doubleValue();
        double turnover = bar.getTurnoverRate().doubleValue();
        if (amount <= 0 || turnover <= 1e-8) {
            return;
        }
        double turnoverFrac = turnover > 1.0 ? turnover / 100.0 : turnover;
        bar.setTotalMarketValue(BigDecimal.valueOf(amount / Math.max(turnoverFrac, 1e-9)));
        bar.setMarketCapType("FLOAT_CAP_APPROX");
        bar.setMarketCapSource("amount_div_turnover");
        bar.setMarketCapReliability("UNKNOWN_LEGACY");
    }

    /**
     * 日 K 入库前的最低质量门槛。停牌日允许零成交量/额，但 OHLC 必须为正且满足
     * low <= open/close <= high；负成交量或成交额属于源数据异常。
     */
    static boolean isValidDailyBar(MarketData bar) {
        if (bar == null || bar.getTradeDate() == null
                || !isPositive(bar.getOpenPrice())
                || !isPositive(bar.getHighPrice())
                || !isPositive(bar.getLowPrice())
                || !isPositive(bar.getClosePrice())) {
            return false;
        }
        BigDecimal high = bar.getHighPrice();
        BigDecimal low = bar.getLowPrice();
        if (high.compareTo(low) < 0
                || high.compareTo(bar.getOpenPrice()) < 0
                || high.compareTo(bar.getClosePrice()) < 0
                || low.compareTo(bar.getOpenPrice()) > 0
                || low.compareTo(bar.getClosePrice()) > 0) {
            return false;
        }
        if (bar.getVolume() != null && bar.getVolume() < 0) {
            return false;
        }
        return bar.getAmount() == null || bar.getAmount().compareTo(BigDecimal.ZERO) >= 0;
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    // ==================== QP-003b：不复权行情回填 + 复权因子推算 ====================

    /** 不复权行情数据来源标识（东方财富 fqt=0）。 */
    public static final String RAW_PRICE_SOURCE = "eastmoney_fqt0";

    /**
     * 回填不复权行情并推算复权因子（QP-003b）。
     *
     * <p>流程：拉取东方财富不复权日 K（fqt=0）→ 去重写入 market_price_raw →
     * 读取已存前复权 market_data → 调用 {@link AdjustFactorDerivationService} 批量推算 adjust_factor。
     * 已存在的 raw / factor 记录跳过（幂等）。
     *
     * <p>口径：raw 价用于成交仿真，factor=qfq_close/raw_close（source=derived_local）；
     * 前复权 market_data 保持不动（双写并存）。
     */
    @Transactional
    public Map<String, Object> backfillRawAndDeriveAdjustFactors(
            String code, String exchangeType, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stock_code", code);
        out.put("exchange_type", exchangeType);
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());

        List<MarketPriceRaw> fetched = fetchRawFromEastmoneyChunked(code, exchangeType, startDate, endDate);
        if (fetched.isEmpty()) {
            out.put("raw_inserted", 0);
            out.put("raw_skipped", 0);
            out.put("message", "未获取到不复权数据");
            return out;
        }
        // 去重：跳过 market_price_raw 已存在的交易日
        List<MarketPriceRaw> toInsert = fetched.stream()
                .filter(r -> marketPriceRawRepo
                        .findByStockCodeAndExchangeTypeAndTradeDate(code, exchangeType, r.getTradeDate())
                        .isEmpty())
                .toList();
        if (!toInsert.isEmpty()) {
            marketPriceRawRepo.saveAll(toInsert);
        }

        // 读取已存前复权行情，推算复权因子
        List<MarketData> qfqBars = marketDataRepo
                .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                        code, exchangeType, startDate, endDate);
        int[] derived = adjustFactorDerivationService.deriveAndPersistForStock(
                code, exchangeType, startDate, endDate, qfqBars, fetched);

        out.put("raw_inserted", toInsert.size());
        out.put("raw_skipped", fetched.size() - toInsert.size());
        out.put("factor_generated", derived[0]);
        out.put("factor_skipped", derived[1]);
        out.put("data_source", RAW_PRICE_SOURCE);
        out.put("message", String.format("不复权新增 %d 条，复权因子生成 %d 条", toInsert.size(), derived[0]));
        return out;
    }

    private List<MarketPriceRaw> fetchRawFromEastmoneyChunked(String code, String exchangeType,
                                                              LocalDate startDate, LocalDate endDate) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days <= EASTMONEY_CHUNK_MAX_DAYS) {
            return fetchRawFromEastmoneyOnce(code, exchangeType, startDate, endDate);
        }
        Map<LocalDate, MarketPriceRaw> byDate = new TreeMap<>();
        LocalDate cur = startDate;
        while (!cur.isAfter(endDate)) {
            LocalDate chunkEnd = cur.plusMonths(1).minusDays(1);
            if (chunkEnd.isAfter(endDate)) {
                chunkEnd = endDate;
            }
            for (MarketPriceRaw r : fetchRawFromEastmoneyOnce(code, exchangeType, cur, chunkEnd)) {
                byDate.put(r.getTradeDate(), r);
            }
            cur = chunkEnd.plusDays(1);
        }
        return new ArrayList<>(byDate.values());
    }

    private List<MarketPriceRaw> fetchRawFromEastmoneyOnce(String code, String exchangeType,
                                                           LocalDate startDate, LocalDate endDate) {
        String secid = toEastmoneySecId(code, exchangeType);
        String beg = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        // fqt=0 不复权；与 fqt=1 前复权同源同结构
        String url = EASTMONEY_KLINE_URL + "?secid=" + secid
                + "&fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=" + FIELDS2
                + "&klt=101&fqt=0"
                + "&beg=" + beg + "&end=" + end;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpHeaders headers = eastmoneyHeaders();
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                String json = resp.getBody();
                if (json == null || json.isBlank()) {
                    return List.of();
                }
                return parseRawKlinesJson(code, exchangeType, json);
            } catch (Exception e) {
                boolean last = attempt >= MAX_ATTEMPTS;
                if (last) {
                    log.warn("[raw] 东方财富不复权行情失败: {} {} [{}..{}] - {}", code, exchangeType, beg, end, e.getMessage());
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

    private List<MarketPriceRaw> parseRawKlinesJson(String code, String exchangeType, String json) throws Exception {
        List<MarketPriceRaw> result = new ArrayList<>();
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
            MarketPriceRaw r = parseRawKlineLine(code, exchangeType, line);
            if (r != null) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 不复权 kline 行解析（格式与 {@link #parseKlineLine} 一致，但写入 {@link MarketPriceRaw}）。
     * 不复权价不推导 preClose/totalMarketValue（成交仿真不需要），仅保留原始 OHLCV/成交额/换手率。
     */
    static MarketPriceRaw parseRawKlineLine(String code, String exchangeType, String line) {
        String[] parts = line.split(",");
        if (parts.length < 7) return null;
        LocalDate tradeDate = parseDateStatic(parts[0]);
        if (tradeDate == null) return null;

        MarketPriceRaw m = new MarketPriceRaw();
        m.setStockCode(code);
        m.setExchangeType(exchangeType);
        m.setTradeDate(tradeDate);
        m.setOpenPrice(toBigDecimalStatic(parts, 1));
        m.setClosePrice(toBigDecimalStatic(parts, 2));
        m.setHighPrice(toBigDecimalStatic(parts, 3));
        m.setLowPrice(toBigDecimalStatic(parts, 4));
        m.setVolume(toLongStatic(parts, 5));
        m.setAmount(toBigDecimalStatic(parts, 6));
        if (parts.length > 10) m.setTurnoverRate(toBigDecimalStatic(parts, 10));
        m.setSource(RAW_PRICE_SOURCE);
        return isValidRawBar(m) ? m : null;
    }

    private static boolean isValidRawBar(MarketPriceRaw bar) {
        if (bar == null || bar.getTradeDate() == null
                || !isPositive(bar.getOpenPrice())
                || !isPositive(bar.getHighPrice())
                || !isPositive(bar.getLowPrice())
                || !isPositive(bar.getClosePrice())) {
            return false;
        }
        BigDecimal high = bar.getHighPrice();
        BigDecimal low = bar.getLowPrice();
        return high.compareTo(low) >= 0
                && high.compareTo(bar.getOpenPrice()) >= 0
                && high.compareTo(bar.getClosePrice()) >= 0
                && low.compareTo(bar.getOpenPrice()) <= 0
                && low.compareTo(bar.getClosePrice()) <= 0;
    }

    private static LocalDate parseDateStatic(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() == 10 && s.contains("-")) return LocalDate.parse(s);
            if (s.length() == 8) return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception ignored) {}
        return null;
    }

    private static BigDecimal toBigDecimalStatic(String[] arr, int i) {
        if (i >= arr.length || arr[i] == null || arr[i].isBlank()) return null;
        try {
            return new BigDecimal(arr[i].trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static long toLongStatic(String[] arr, int i) {
        if (i >= arr.length || arr[i] == null || arr[i].isBlank()) return 0L;
        String t = arr[i].replace(",", "").trim();
        if (t.isEmpty() || "-".equals(t)) return 0L;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            try { return (long) Double.parseDouble(t); } catch (Exception ignored) { return 0L; }
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() == 10 && s.contains("-")) return LocalDate.parse(s);
            if (s.length() == 8) return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception ignored) {}
        return null;
    }

    private BigDecimal toBigDecimal(String[] arr, int i) {
        if (i >= arr.length || arr[i] == null || arr[i].isBlank()) return null;
        try {
            return new BigDecimal(arr[i].trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(String[] arr, int i) {
        if (i >= arr.length || arr[i] == null || arr[i].isBlank()) return null;
        try {
            return Long.parseLong(arr[i].trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String toEastmoneySecId(String code, String ex) {
        if ("SSE".equals(ex)) return "1." + code;
        if ("BSE".equals(ex)) return "0." + code;
        if (code.startsWith("4") || code.startsWith("8") || code.startsWith("92")) return "0." + code;
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "1." + code;
        return "0." + code;
    }

    private String inferExchangeType(String code) {
        if (code == null || code.length() < 4) return "SZSE";
        if (code.startsWith("4") || code.startsWith("8") || code.startsWith("92")) return "BSE";
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "SSE";
        return "SZSE";
    }
}
