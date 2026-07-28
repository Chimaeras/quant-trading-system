package com.quant.service;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.StockInfo;
import com.quant.entity.WatchlistItem;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.WatchlistItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    @Mock
    private WatchlistItemRepository watchlistItemRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;
    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private SelectorResultItemRepository selectorResultItemRepository;

    private WatchlistService service() {
        return new WatchlistService(watchlistItemRepository, stockInfoRepository,
                selectorResultRepository, selectorResultItemRepository);
    }

    @Test
    void addCreatesNewItemAndAutoFillsName() {
        when(watchlistItemRepository.findByUserIdAndStockCodeAndExchangeType(1L, "000001", "SZSE"))
                .thenReturn(Optional.empty());
        StockInfo info = new StockInfo();
        info.setStockName("平安银行");
        when(stockInfoRepository.findByStockCodeAndExchangeType("000001", "SZSE"))
                .thenReturn(Optional.of(info));

        Map<String, Object> out = service().add(1L, "000001", "SZSE", "关注");

        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(watchlistItemRepository).save(captor.capture());
        WatchlistItem saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getStockCode()).isEqualTo("000001");
        assertThat(saved.getStockName()).isEqualTo("平安银行");
        assertThat(saved.getNote()).isEqualTo("关注");
        assertThat(out.get("stock_name")).isEqualTo("平安银行");
    }

    @Test
    void addIsIdempotentAndUpdateNoteWhenExists() {
        WatchlistItem existing = new WatchlistItem();
        existing.setId(5L);
        existing.setUserId(1L);
        existing.setStockCode("000001");
        existing.setExchangeType("SZSE");
        existing.setStockName("平安银行");
        existing.setNote("旧备注");
        when(watchlistItemRepository.findByUserIdAndStockCodeAndExchangeType(1L, "000001", "SZSE"))
                .thenReturn(Optional.of(existing));

        service().add(1L, "000001", "SZSE", "新备注");

        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(watchlistItemRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
        assertThat(captor.getValue().getNote()).isEqualTo("新备注");
        // 已有名称时不重新查 stock_info
        verify(stockInfoRepository, never()).findByStockCodeAndExchangeType(any(), any());
    }

    @Test
    void removeDeletesByUserAndStockKey() {
        when(watchlistItemRepository.deleteByUserIdAndStockCodeAndExchangeType(1L, "000001", "SZSE"))
                .thenReturn(1L);

        Map<String, Object> out = service().remove(1L, "000001", "SZSE");

        assertThat(out.get("deleted")).isEqualTo(true);
    }

    @Test
    void listReturnsItemsOrderedByCreatedAtDesc() {
        WatchlistItem a = new WatchlistItem();
        a.setId(1L);
        a.setUserId(1L);
        a.setStockCode("000001");
        a.setExchangeType("SZSE");
        a.setStockName("平安银行");
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(a));

        List<Map<String, Object>> out = service().list(1L);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("stock_code")).isEqualTo("000001");
    }

    @SuppressWarnings("unchecked")
    @Test
    void signalCoverageMarksHitsAndMisses() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        WatchlistItem a = new WatchlistItem();
        a.setUserId(1L);
        a.setStockCode("000001");
        a.setExchangeType("SZSE");
        a.setStockName("平安银行");
        WatchlistItem b = new WatchlistItem();
        b.setUserId(1L);
        b.setStockCode("000002");
        b.setExchangeType("SZSE");
        b.setStockName("万科A");
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(a, b));

        SelectorResult classic = new SelectorResult();
        classic.setResultId("R1");
        classic.setStrategyType("CLASSIC_SELECTOR");
        classic.setTradeDate(d);
        when(selectorResultRepository.findLatestSuccessPerStrategy()).thenReturn(List.of(classic));

        SelectorResultItem hit = new SelectorResultItem();
        hit.setResultId("R1");
        hit.setStockCode("000001");
        hit.setExchangeType("SZSE");
        hit.setRankNo(3);
        hit.setFinalScore(75.0);
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(hit));

        Map<String, Object> out = service().signalCoverage(1L);

        assertThat(out.get("trade_date")).isEqualTo("2025-01-02");
        assertThat(out.get("total")).isEqualTo(2);
        assertThat(out.get("hit_count")).isEqualTo(1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(2);
        Map<String, Object> first = items.get(0);
        assertThat(first.get("hit_count")).isEqualTo(1);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) first.get("hit_strategies");
        assertThat(hits.get(0).get("strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(hits.get(0).get("rank_no")).isEqualTo(3);
        assertThat(items.get(1).get("hit_count")).isEqualTo(0);
    }

    @Test
    void signalCoverageReturnsEmptyWhenWatchlistEmpty() {
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        Map<String, Object> out = service().signalCoverage(1L);
        assertThat(out.get("total")).isEqualTo(0);
        assertThat(out.get("hit_count")).isEqualTo(0);
    }

    @Test
    void batchAddInfersExchangeAndSkipsEmpty() {
        // 600 开头推断 SSE；000 开头推断 SZSE；空行跳过
        when(watchlistItemRepository.findByUserIdAndStockCodeAndExchangeType(1L, "600000", "SSE"))
                .thenReturn(Optional.empty());
        when(watchlistItemRepository.findByUserIdAndStockCodeAndExchangeType(1L, "000001", "SZSE"))
                .thenReturn(Optional.empty());
        when(stockInfoRepository.findByStockCodeAndExchangeType(any(), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> out = service().batchAdd(1L, List.of(
                new String[]{"600000"},
                new String[]{"000001", "SZSE"},
                new String[]{""}
        ));

        assertThat(out.get("added")).isEqualTo(2);
        assertThat(out.get("skipped")).isEqualTo(1);
    }
}
