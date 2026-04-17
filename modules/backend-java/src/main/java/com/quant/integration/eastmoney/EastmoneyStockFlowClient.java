package com.quant.integration.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 东方财富个股资金流向日 K（近若干日主力净流入），用于 5 日累计（亿元）。
 */
@Slf4j
@Component
public class EastmoneyStockFlowClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String FLOW_KLINE =
            "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get";

    public double sumMainNetLastNDays(String stockCode, String exchangeType, int days) {
        String code = stockCode != null ? stockCode.split("\\.")[0].trim() : "";
        if (code.isEmpty() || days <= 0) {
            return 0;
        }
        String secid = toSecId(code, exchangeType);
        String url = FLOW_KLINE + "?secid=" + secid + "&lmt=" + Math.min(days + 5, 60) + "&klt=101";
        try {
            HttpHeaders h = new HttpHeaders();
            h.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            h.set(HttpHeaders.REFERER, "https://quote.eastmoney.com/");
            String json = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class).getBody();
            if (json == null || json.isBlank()) {
                return 0;
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            JsonNode klines = data.path("klines");
            if (!klines.isArray() || klines.isEmpty()) {
                return 0;
            }
            int n = klines.size();
            double sumYi = 0;
            int taken = 0;
            for (int i = n - 1; i >= 0 && taken < days; i--, taken++) {
                String line = klines.get(i).asText("");
                // 常见格式：日期,主力净流入,小单,中单,大单,超大单,...（单位多为元）
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    try {
                        double v = Double.parseDouble(parts[1].trim());
                        sumYi += v / 1e8;
                    } catch (NumberFormatException ignored) {
                        // try position 2
                        if (parts.length >= 3) {
                            try {
                                double v = Double.parseDouble(parts[2].trim());
                                sumYi += v / 1e8;
                            } catch (NumberFormatException ignored2) {
                                // skip
                            }
                        }
                    }
                }
            }
            return sumYi;
        } catch (Exception e) {
            log.debug("fflow kline failed {} {}: {}", code, exchangeType, e.getMessage());
            return 0;
        }
    }

    private static String toSecId(String code, String ex) {
        if (ex != null && ex.equals("SSE")) {
            return "1." + code;
        }
        if (ex != null && ex.equals("BSE")) {
            return "0." + code;
        }
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) {
            return "1." + code;
        }
        return "0." + code;
    }
}
