package com.quant.service;

import com.quant.entity.StockInfo;
import com.quant.repository.StockInfoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenerServiceTest {

    @Mock
    private StockInfoRepository stockInfoRepository;

    private ScreenerService service() {
        return new ScreenerService(stockInfoRepository);
    }

    private static StockInfo info(String code, String exchange, String industry, LocalDate listDate) {
        StockInfo s = new StockInfo();
        s.setStockCode(code);
        s.setExchangeType(exchange);
        s.setStockName("测试" + code);
        s.setIndustry(industry);
        s.setListDate(listDate);
        return s;
    }

    @Test
    void screenFiltersByIndustryAndMinListingDays() {
        LocalDate ref = LocalDate.of(2025, 1, 2);
        // 000001 银行 上市 1000 天；000002 银行 上市 10 天；000003 地产 上市 1000 天
        Page<StockInfo> page = new PageImpl<>(List.of(
                info("000001", "SZSE", "银行", ref.minusDays(1000)),
                info("000002", "SZSE", "银行", ref.minusDays(10)),
                info("000003", "SZSE", "地产", ref.minusDays(1000))
        ), PageRequest.of(0, 50), 3);
        when(stockInfoRepository.search(eq(null), eq("SZSE"), any()))
                .thenReturn(page);

        // 筛选：银行 + 上市≥60 天
        Map<String, Object> out = service().screen(null, "SZSE", "银行", 60, ref, 1, 50);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("stock_code")).isEqualTo("000001");
        assertThat(out.get("total_before_filter")).isEqualTo(3L);
        assertThat(out.get("returned")).isEqualTo(1);
    }

    @Test
    void screenExcludesMissingListDateWhenMinListingDaysSet() {
        LocalDate ref = LocalDate.of(2025, 1, 2);
        StockInfo noListDate = info("000004", "SZSE", "银行", null);
        Page<StockInfo> page = new PageImpl<>(List.of(noListDate), PageRequest.of(0, 50), 1);
        when(stockInfoRepository.search(any(), any(), any())).thenReturn(page);

        Map<String, Object> out = service().screen(null, null, null, 60, ref, 1, 50);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).isEmpty();
    }
}
