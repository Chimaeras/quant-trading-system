package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.WatchlistItem;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.WatchlistItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistAlertServiceTest {

    @Mock
    private WatchlistItemRepository watchlistItemRepository;
    @Mock
    private MarketDataRepository marketDataRepository;

    private WatchlistAlertService service() {
        return new WatchlistAlertService(watchlistItemRepository, marketDataRepository);
    }

    private static WatchlistItem item(String code, String exchange, String name) {
        WatchlistItem w = new WatchlistItem();
        w.setUserId(1L);
        w.setStockCode(code);
        w.setExchangeType(exchange);
        w.setStockName(name);
        return w;
    }

    private static MarketData bar(LocalDate d, double close, double pct, long volume) {
        MarketData m = new MarketData();
        m.setStockCode("000001");
        m.setExchangeType("SZSE");
        m.setTradeDate(d);
        m.setClosePrice(BigDecimal.valueOf(close));
        m.setChangePct(BigDecimal.valueOf(pct));
        m.setVolume(volume);
        return m;
    }

    @SuppressWarnings("unchecked")
    @Test
    void scanEmptyWatchlistReturnsEmpty() {
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        Map<String, Object> out = service().scan(1L, null, null, null);
        assertThat(out.get("total_scanned")).isEqualTo(0);
        assertThat(out.get("total_alerted")).isEqualTo(0);
        assertThat((List<Map<String, Object>>) out.get("alerts")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void scanDetectsPctBreakoutVolumeSurgeAndNewHigh() {
        LocalDate today = LocalDate.now();
        // 5 个历史 bar（量 1000、close 9），1 个最新 bar（量 3000、close 10、涨幅 6%）
        // 注意：change_pct 在 DB 中按百分比数值存储（6.0 表示 6%），与 HistoricalDataService 入库口径一致
        List<MarketData> bars = List.of(
                bar(today.minusDays(6), 9.0, 0.0, 1000),
                bar(today.minusDays(5), 9.0, 0.0, 1000),
                bar(today.minusDays(4), 9.0, 0.0, 1000),
                bar(today.minusDays(3), 9.0, 0.0, 1000),
                bar(today.minusDays(2), 9.0, 0.0, 1000),
                bar(today.minusDays(1), 10.0, 6.0, 3000)
        );
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(item("000001", "SZSE", "平安银行")));
        when(marketDataRepository.findByStockKeyInAndTradeDateBetween(any(), any(), any()))
                .thenReturn(bars);

        Map<String, Object> out = service().scan(1L, null, null, null);

        assertThat(out.get("total_scanned")).isEqualTo(1);
        assertThat(out.get("total_alerted")).isEqualTo(1);
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) out.get("alerts");
        assertThat(alerts).hasSize(1);
        List<String> signals = (List<String>) alerts.get(0).get("signals");
        assertThat(signals).anyMatch(s -> s.startsWith("涨幅突破"));
        assertThat(signals).anyMatch(s -> s.startsWith("成交量放大"));
        assertThat(signals).anyMatch(s -> s.startsWith("创"));
        // 6% 未逼近涨停（主板 10%，阈值 9%）
        assertThat(signals).noneMatch("逼近涨停"::equals);
    }

    @SuppressWarnings("unchecked")
    @Test
    void scanSkipsStocksWithNoQuote() {
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(item("000002", "SZSE", "万科A")));
        when(marketDataRepository.findByStockKeyInAndTradeDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> out = service().scan(1L, null, null, null);
        assertThat(out.get("total_scanned")).isEqualTo(1);
        assertThat(out.get("total_alerted")).isEqualTo(0);
        assertThat((List<Map<String, Object>>) out.get("alerts")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void scanNoAlertsWhenQuiet() {
        LocalDate today = LocalDate.now();
        // 平静行情：涨幅 0、量持平、close 持平
        List<MarketData> bars = List.of(
                bar(today.minusDays(3), 10.0, 0.0, 1000),
                bar(today.minusDays(2), 10.0, 0.0, 1000),
                bar(today.minusDays(1), 10.0, 0.0, 1000)
        );
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(item("000001", "SZSE", "平安银行")));
        when(marketDataRepository.findByStockKeyInAndTradeDateBetween(any(), any(), any()))
                .thenReturn(bars);

        Map<String, Object> out = service().scan(1L, null, null, null);
        assertThat(out.get("total_alerted")).isEqualTo(0);
        assertThat((List<Map<String, Object>>) out.get("alerts")).isEmpty();
    }
}
