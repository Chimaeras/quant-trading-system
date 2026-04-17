package com.quant.integration.gdelt;

import java.util.LinkedHashMap;
import java.util.Map;

public record GdeltArticle(
        String title,
        String url,
        String seenDate,
        Long seenEpochMillis,
        double tone,
        String domain,
        String language,
        String sourceCountry
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("url", url);
        m.put("seen_date", seenDate);
        m.put("seen_epoch_ms", seenEpochMillis);
        m.put("tone", tone);
        m.put("domain", domain);
        m.put("language", language);
        m.put("source_country", sourceCountry);
        return m;
    }
}

