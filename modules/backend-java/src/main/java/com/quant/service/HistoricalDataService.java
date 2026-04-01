package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 历史行情服务 - 从东方财富接口获取指定日期范围内的日K数据，
 * 检查数据库是否已有，仅插入缺失的交易日数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalDataService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MarketDataRepository marketDataRepo;

    private static final String EASTMONEY_KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
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

        List<MarketData> fetched = fetchFromEastmoney(code, ex, startDate, endDate);
        if (fetched.isEmpty()) {
            return Map.of("inserted", 0, "skipped", 0, "total", 0, "message", "未获取到数据，请检查股票代码或网络");
        }

        Set<LocalDate> existingDates = new HashSet<>(
                marketDataRepo.findExistingTradeDates(code, ex, startDate, endDate));
        List<MarketData> toInsert = fetched.stream()
                .filter(m -> !existingDates.contains(m.getTradeDate()))
                .toList();
        marketDataRepo.saveAll(toInsert);

        return Map.of(
                "inserted", toInsert.size(),
                "skipped", fetched.size() - toInsert.size(),
                "total", fetched.size(),
                "message", String.format("新增 %d 条，跳过已存在 %d 条", toInsert.size(), fetched.size() - toInsert.size())
        );
    }

    private List<MarketData> fetchFromEastmoney(String code, String exchangeType,
                                                 LocalDate startDate, LocalDate endDate) {
        String secid = toEastmoneySecId(code, exchangeType);
        String beg = startDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = endDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = EASTMONEY_KLINE_URL + "?secid=" + secid
                + "&fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=" + FIELDS2
                + "&klt=101&fqt=1"
                + "&beg=" + beg + "&end=" + end;

        List<MarketData> result = new ArrayList<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String json = resp.getBody();
            if (json == null || json.isBlank()) return result;

            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isNull() || !data.isObject()) return result;
            JsonNode klines = data.path("klines");
            if (!klines.isArray()) return result;

            for (JsonNode k : klines) {
                String line = k.asText("");
                if (line.isBlank()) continue;
                MarketData m = parseKlineLine(code, exchangeType, line);
                if (m != null) result.add(m);
            }
        } catch (Exception e) {
            log.warn("获取历史行情失败: {} {} - {}", code, exchangeType, e.getMessage());
        }
        return result;
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
        if (m.getClosePrice() != null && m.getChangePct() != null) {
            try {
                BigDecimal pct = m.getChangePct().divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);
                BigDecimal divisor = BigDecimal.ONE.add(pct);
                if (divisor.compareTo(BigDecimal.ZERO) != 0) {
                    m.setPreClose(m.getClosePrice().divide(divisor, 4, java.math.RoundingMode.HALF_UP));
                }
            } catch (Exception ignored) {}
        }
        return m;
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
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "1." + code;
        return "0." + code;
    }

    private String inferExchangeType(String code) {
        if (code == null || code.length() < 4) return "SZSE";
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "SSE";
        if (code.startsWith("8") || code.startsWith("4")) return "BSE";
        return "SZSE";
    }
}
