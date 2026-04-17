package com.quant.integration.gdelt;

import java.util.LinkedHashMap;
import java.util.Map;

public record GdeltEvent(
        String globalEventId,
        Long sqlDate,
        String eventCode,
        double goldsteinScale,
        double avgTone,
        String actor1CountryCode,
        String actor2CountryCode,
        String sourceUrl
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("global_event_id", globalEventId);
        m.put("sql_date", sqlDate);
        m.put("event_code", eventCode);
        m.put("goldstein_scale", goldsteinScale);
        m.put("avg_tone", avgTone);
        m.put("actor1_country", actor1CountryCode);
        m.put("actor2_country", actor2CountryCode);
        m.put("source_url", sourceUrl);
        return m;
    }
}

