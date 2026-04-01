package com.quant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 实时行情服务 - 从东方财富在线接口获取最新价格
 * 用于行情分析模块补充实时数据
 */
@Slf4j
@Service
public class RealtimeQuoteService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EASTMONEY_URL = "https://push2.eastmoney.com/api/qt/stock/get";
    /** 东方财富字段: f43最新价 f44最高 f45最低 f46今开 f47成交量 f48成交额 f50涨跌幅 f51涨跌额 f57代码 f58名称 f60昨收 */
    private static final String FIELDS = "f43,f44,f45,f46,f47,f48,f50,f51,f57,f58,f60";

    /**
     * 获取股票实时行情
     * @param stockCode 股票代码(如 000001, 600519)
     * @param exchangeType 交易所 SSE/SZSE/BSE
     * @return 行情数据，失败返回空 Map
     */
    public Map<String, Object> fetchRealtimeQuote(String stockCode, String exchangeType) {
        String code = (stockCode != null ? stockCode : "").split("\\.")[0].trim();
        if (code.isEmpty()) return Map.of();

        String secid = toEastmoneySecId(code, exchangeType);
        String url = EASTMONEY_URL + "?secid=" + secid + "&fields=" + FIELDS;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String json = resp.getBody();
            if (json == null || json.isBlank()) return Map.of();

            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isNull() || !data.isObject()) return Map.of();

            Map<String, Object> result = new HashMap<>();
            result.put("stock_code", code);
            result.put("stock_name", getStr(data, "f58"));
            result.put("exchange_type", exchangeType);

            // 东方财富价格类字段以「分」为单位，需除以100转为元
            BigDecimal close = scalePrice(getDecimal(data, "f43"));
            BigDecimal preClose = scalePrice(getDecimal(data, "f60"));
            result.put("close_price", close);
            result.put("pre_close", preClose);
            result.put("open_price", scalePrice(getDecimal(data, "f46")));
            result.put("high_price", scalePrice(getDecimal(data, "f44")));
            result.put("low_price", scalePrice(getDecimal(data, "f45")));
            result.put("volume", getLong(data, "f47"));
            result.put("amount", getDecimal(data, "f48")); // 成交额已是元

            // 涨跌额、涨跌幅优先用收盘价与昨收自行计算，避免API单位不一致
            if (preClose != null && preClose.compareTo(BigDecimal.ZERO) != 0 && close != null) {
                BigDecimal changeAmt = close.subtract(preClose);
                result.put("change_amount", changeAmt);
                result.put("change_pct", changeAmt.divide(preClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
            } else {
                result.put("change_amount", scalePrice(getDecimal(data, "f51")));
                BigDecimal changePct = getDecimal(data, "f50");
                if (changePct != null && changePct.abs().compareTo(BigDecimal.valueOf(100)) <= 0) {
                    result.put("change_pct", changePct);
                } else if (changePct != null) {
                    result.put("change_pct", changePct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                }
            }

            return result;
        } catch (Exception e) {
            log.debug("获取实时行情失败: {} {} - {}", code, exchangeType, e.getMessage());
            return Map.of();
        }
    }

    private String toEastmoneySecId(String code, String ex) {
        if (ex != null && ex.equals("SSE")) return "1." + code;
        if (ex != null && ex.equals("BSE")) return "0." + code;
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "1." + code;
        return "0." + code;
    }

    private String getStr(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isNull() || v.isMissingNode() ? null : v.asText("");
    }

    /** 东方财富价格类字段以分为单位，除以100转为元 */
    private BigDecimal scalePrice(BigDecimal v) {
        if (v == null) return null;
        return v.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal getDecimal(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isNull() || v.isMissingNode()) return null;
        try {
            return BigDecimal.valueOf(v.asDouble());
        } catch (Exception e) {
            return null;
        }
    }

    private Long getLong(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isNull() || v.isMissingNode()) return null;
        try {
            return v.asLong();
        } catch (Exception e) {
            return null;
        }
    }
}
