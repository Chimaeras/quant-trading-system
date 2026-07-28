package com.quant.service;

import com.quant.entity.StockInfo;
import com.quant.entity.WatchlistItem;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.WatchlistItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private WatchlistService service() {
        return new WatchlistService(watchlistItemRepository, stockInfoRepository);
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
}
