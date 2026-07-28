package com.quant.service;

import com.quant.entity.ScreenerTemplate;
import com.quant.entity.StockInfo;
import com.quant.repository.ScreenerTemplateRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScreenerServiceTest {

    @Mock
    private StockInfoRepository stockInfoRepository;
    @Mock
    private ScreenerTemplateRepository screenerTemplateRepository;

    private ScreenerService service() {
        return new ScreenerService(stockInfoRepository, screenerTemplateRepository);
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
        Map<String, Object> out = service().screen(null, "SZSE", "银行", 60, false, ref, 1, 50);

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

        Map<String, Object> out = service().screen(null, null, null, 60, false, ref, 1, 50);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).isEmpty();
    }

    @Test
    void saveTemplateCreatesNewWhenNameAbsent() {
        when(screenerTemplateRepository.findByUserIdAndName(1L, "银行筛选")).thenReturn(Optional.empty());
        when(screenerTemplateRepository.save(any())).thenAnswer(inv -> {
            ScreenerTemplate t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });

        Map<String, Object> out = service().saveTemplate(1L, "银行筛选", "银行", "SZSE", "银行", 60, true);

        assertThat(out.get("name")).isEqualTo("银行筛选");
        assertThat(out.get("keyword")).isEqualTo("银行");
        assertThat(out.get("industry")).isEqualTo("银行");
        assertThat(out.get("min_listing_days")).isEqualTo(60);
        verify(screenerTemplateRepository).save(any());
    }

    @Test
    void saveTemplateUpdatesExistingWhenNamePresent() {
        ScreenerTemplate existing = new ScreenerTemplate();
        existing.setId(5L);
        existing.setUserId(1L);
        existing.setName("银行筛选");
        when(screenerTemplateRepository.findByUserIdAndName(1L, "银行筛选")).thenReturn(Optional.of(existing));
        when(screenerTemplateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> out = service().saveTemplate(1L, "银行筛选", null, null, "银行", 120, false);

        assertThat(out.get("id")).isEqualTo(5L);
        assertThat(out.get("min_listing_days")).isEqualTo(120);
        verify(screenerTemplateRepository).save(existing);
    }

    @Test
    void listTemplatesReturnsAllForUser() {
        ScreenerTemplate t = new ScreenerTemplate();
        t.setId(1L);
        t.setUserId(1L);
        t.setName("模板A");
        t.setIndustry("银行");
        when(screenerTemplateRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(t));

        List<Map<String, Object>> out = service().listTemplates(1L);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("name")).isEqualTo("模板A");
    }

    @Test
    void deleteTemplateReturnsDeletedFlag() {
        when(screenerTemplateRepository.deleteByUserIdAndId(1L, 7L)).thenReturn(1L);
        Map<String, Object> out = service().deleteTemplate(1L, 7L);
        assertThat(out.get("deleted")).isEqualTo(true);

        when(screenerTemplateRepository.deleteByUserIdAndId(1L, 8L)).thenReturn(0L);
        Map<String, Object> out2 = service().deleteTemplate(1L, 8L);
        assertThat(out2.get("deleted")).isEqualTo(false);
    }

    @Test
    void screenExcludesStStocksWhenEnabled() {
        LocalDate ref = LocalDate.of(2025, 1, 2);
        StockInfo normal = info("000001", "SZSE", "银行", ref.minusDays(1000));
        StockInfo st = info("000002", "SZSE", "银行", ref.minusDays(1000));
        st.setStockName("ST测试");
        Page<StockInfo> page = new PageImpl<>(List.of(normal, st), PageRequest.of(0, 50), 2);
        when(stockInfoRepository.search(any(), any(), any())).thenReturn(page);

        Map<String, Object> out = service().screen(null, null, null, null, true, ref, 1, 50);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("stock_code")).isEqualTo("000001");
    }
}
