package com.quant.service;

import com.quant.entity.SecurityMasterHistory;
import com.quant.entity.StockInfo;
import com.quant.repository.StockInfoRepository;
import com.quant.research.SecurityMasterHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PIT 股票池视图测试：loadAsOf 应按 as_of 过滤「已退市」，并按可靠性披露 LIMITED。
 */
@ExtendWith(MockitoExtension.class)
class PointInTimeUniverseProviderTest {

    @Mock
    private SecurityMasterHistoryService securityMasterHistoryService;
    @Mock
    private StockInfoRepository stockInfoRepository;
    @InjectMocks
    private PointInTimeUniverseProvider provider;

    private SecurityMasterHistory master(String code, String exchange, LocalDate delistDate, String reliability) {
        SecurityMasterHistory h = new SecurityMasterHistory();
        h.setStockCode(code);
        h.setExchangeType(exchange);
        h.setStockName(code);
        h.setListDate(LocalDate.of(2010, 1, 1));
        h.setDelistDate(delistDate);
        h.setReliability(reliability);
        return h;
    }

    @Test
    void pitPathExcludesDelistedAndReportsOfficialSource() {
        LocalDate asOf = LocalDate.of(2025, 6, 1);
        SecurityMasterHistory active = master("000001", "SZSE", null, PointInTimeUniverseProvider.RELIABILITY_OFFICIAL);
        SecurityMasterHistory delisted = master("600000", "SSE", LocalDate.of(2025, 5, 1), PointInTimeUniverseProvider.RELIABILITY_OFFICIAL);
        when(securityMasterHistoryService.findEffectiveMasters(asOf)).thenReturn(List.of(active, delisted));

        PointInTimeUniverseProvider.UniverseView view = provider.loadAsOf(asOf);

        assertThat(view.universeSize()).isEqualTo(1); // 已退市的 600000 被排除
        assertThat(view.byKey()).containsKey("000001|SZSE");
        assertThat(view.byKey()).doesNotContainKey("600000|SSE");
        assertThat(view.masterSource()).isEqualTo(PointInTimeUniverseProvider.SOURCE_PIT_HISTORY);
        assertThat(view.masterReliability()).isEqualTo(PointInTimeUniverseProvider.RELIABILITY_OFFICIAL);
        assertThat(view.pitLimited()).isFalse();
    }

    @Test
    void pitPathMarksLimitedWhenAnyReliabilityLimited() {
        LocalDate asOf = LocalDate.of(2025, 6, 1);
        SecurityMasterHistory official = master("000001", "SZSE", null, PointInTimeUniverseProvider.RELIABILITY_OFFICIAL);
        SecurityMasterHistory limited = master("600000", "SSE", null, PointInTimeUniverseProvider.RELIABILITY_LIMITED);
        when(securityMasterHistoryService.findEffectiveMasters(asOf)).thenReturn(List.of(official, limited));

        PointInTimeUniverseProvider.UniverseView view = provider.loadAsOf(asOf);

        assertThat(view.universeSize()).isEqualTo(2);
        assertThat(view.masterReliability()).isEqualTo(PointInTimeUniverseProvider.RELIABILITY_LIMITED);
        assertThat(view.pitLimited()).isTrue();
    }

    @Test
    void fallbackToCurrentSnapshotIsMarkedPitLimited() {
        LocalDate asOf = LocalDate.of(2025, 6, 1);
        when(securityMasterHistoryService.findEffectiveMasters(asOf)).thenReturn(List.of());

        StockInfo s1 = new StockInfo();
        s1.setStockCode("000001");
        s1.setExchangeType("SZSE");
        StockInfo s2 = new StockInfo();
        s2.setStockCode("600000");
        s2.setExchangeType("SSE");
        when(stockInfoRepository.findAll()).thenReturn(List.of(s1, s2));

        PointInTimeUniverseProvider.UniverseView view = provider.loadAsOf(asOf);

        assertThat(view.masterSource()).isEqualTo(PointInTimeUniverseProvider.SOURCE_STOCK_INFO_FALLBACK);
        assertThat(view.pitLimited()).isTrue();
        assertThat(view.universeSize()).isEqualTo(2);
        assertThat(view.lineage().get("universe_gate")).isEqualTo("PIT_LIMITED");
    }
}
