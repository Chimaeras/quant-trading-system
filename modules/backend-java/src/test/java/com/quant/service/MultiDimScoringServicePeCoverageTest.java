package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-4：行业平均 PE 只由 PE 非空个股聚合，PE 缺失个股被静默排除。
 * 这里验证覆盖率（含PE个股数/行业个股数）与丢弃计数被正确披露。
 */
class MultiDimScoringServicePeCoverageTest {

    // computeIndustryPeAvg 不依赖注入字段，用全 null 构造即可
    private final MultiDimScoringService service = new MultiDimScoringService(null, null, null, null, null, null);

    @Test
    void industryPeCoverageReflectsNullPeDrop() {
        Map<String, MarketData> mdToday = new HashMap<>();
        mdToday.put("600001|SH", md(10.0));
        mdToday.put("600002|SH", md(20.0));
        mdToday.put("600003|SH", md(null));   // null PE → 被排除
        mdToday.put("000001|SZ", md(5.0));
        mdToday.put("000002|SZ", md(null));   // null PE → 被排除
        mdToday.put("300001|SZ", md(7.0));    // 无行业 → 不计入

        Map<String, StockInfo> infoByKey = new HashMap<>();
        infoByKey.put("600001|SH", info("华立", "PE"));
        infoByKey.put("600002|SH", info("华立2", "PE"));
        infoByKey.put("600003|SH", info("华立3", "PE"));
        infoByKey.put("000001|SZ", info("银行a", "BANK"));
        infoByKey.put("000002|SZ", info("银行b", "BANK"));
        infoByKey.put("300001|SZ", info("无行业", null));

        MultiDimScoringService.IndustryAvgs avgs = service.computeIndustryPeAvg(mdToday, infoByKey);

        // PE 行业：2 只有值 / 3 只总数 → 覆盖率 2/3
        assertThat(avgs.peCoverageByIndustry().get("PE")).isEqualTo(2.0 / 3.0);
        // 平均 PE 仅由非空个股算：(10+20)/2
        assertThat(avgs.avgPeByIndustry().get("PE")).isEqualTo(15.0);
        assertThat(avgs.peStocksByIndustry().get("PE")).isEqualTo(2);
        assertThat(avgs.industryStocksByIndustry().get("PE")).isEqualTo(3);
        // BANK：1 有值 / 2 总数
        assertThat(avgs.peCoverageByIndustry().get("BANK")).isEqualTo(1.0 / 2.0);
        // 被排除的 null PE 个股 = 600003 + 000002 = 2
        assertThat(avgs.nullPeDropped()).isEqualTo(2);
        // 无行业个股不计入任何行业桶
        assertThat(avgs.industryStocksByIndustry()).doesNotContainKey(null);
    }

    @Test
    void fullCoverageIndustryIsNotMarkedPartial() {
        Map<String, MarketData> mdToday = new HashMap<>();
        mdToday.put("600001|SH", md(10.0));
        mdToday.put("600002|SH", md(20.0));
        Map<String, StockInfo> infoByKey = new HashMap<>();
        infoByKey.put("600001|SH", info("a", "PE"));
        infoByKey.put("600002|SH", info("b", "PE"));

        MultiDimScoringService.IndustryAvgs avgs = service.computeIndustryPeAvg(mdToday, infoByKey);
        assertThat(avgs.peCoverageByIndustry().get("PE")).isEqualTo(1.0);
        assertThat(avgs.nullPeDropped()).isZero();
    }

    private static MarketData md(Double pe) {
        MarketData m = new MarketData();
        if (pe != null) {
            m.setPeRatio(BigDecimal.valueOf(pe));
        }
        return m;
    }

    private static StockInfo info(String name, String industry) {
        StockInfo s = new StockInfo();
        s.setStockName(name);
        s.setIndustry(industry);
        return s;
    }
}
