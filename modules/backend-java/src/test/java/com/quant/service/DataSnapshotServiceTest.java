package com.quant.service;

import com.quant.entity.DataSnapshot;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.DataSnapshotPartitionManifestRepository;
import com.quant.repository.DataSnapshotRepository;
import com.quant.repository.IndexQuoteRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.TradingCalendarDayRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QP-006 数据快照：行数收集 + source 分布 + hash 计算 + snapshotId 生成测试。
 */
@ExtendWith(MockitoExtension.class)
class DataSnapshotServiceTest {

    @Mock
    private DataSnapshotRepository dataSnapshotRepository;
    @Mock
    private DataSnapshotPartitionManifestRepository partitionManifestRepository;
    @Mock
    private MarketDataRepository marketDataRepository;
    @Mock
    private MarketPriceRawRepository marketPriceRawRepository;
    @Mock
    private AdjustFactorRepository adjustFactorRepository;
    @Mock
    private SecurityMasterHistoryRepository securityMasterHistoryRepository;
    @Mock
    private TradingCalendarDayRepository tradingCalendarDayRepository;
    @Mock
    private IndexQuoteRepository indexQuoteRepository;

    @InjectMocks
    private DataSnapshotService service;

    @Test
    void createSnapshotCollectsRowCountsAndComputesHash() {
        LocalDate cutoff = LocalDate.of(2025, 7, 31);
        when(marketDataRepository.count()).thenReturn(10000L);
        when(marketPriceRawRepository.countAll()).thenReturn(8000L);
        when(adjustFactorRepository.countAll()).thenReturn(7000L);
        when(securityMasterHistoryRepository.count()).thenReturn(4000L);
        when(tradingCalendarDayRepository.count()).thenReturn(500L);
        when(indexQuoteRepository.count()).thenReturn(1000L);

        when(adjustFactorRepository.countBySource("derived_local")).thenReturn(7000L);
        when(adjustFactorRepository.countBySource("official")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("current_snapshot")).thenReturn(4000L);
        when(securityMasterHistoryRepository.countBySource("eastmoney")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("tushare")).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource("eastmoney_derived")).thenReturn(500L);
        when(tradingCalendarDayRepository.countBySource("tushare")).thenReturn(0L);
        when(indexQuoteRepository.countBySource("eastmoney")).thenReturn(1000L);
        when(indexQuoteRepository.countBySource("tushare")).thenReturn(0L);

        when(marketDataRepository.findMinTradeDate()).thenReturn(java.util.Optional.of(LocalDate.of(2023, 1, 3)));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(cutoff));
        when(marketPriceRawRepository.findMinTradeDate()).thenReturn(java.util.Optional.of(LocalDate.of(2023, 1, 3)));
        when(marketPriceRawRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(cutoff));

        when(dataSnapshotRepository.findBySnapshotIdStartingWith("20250731")).thenReturn(List.of());

        DataSnapshot snap = service.createSnapshot(cutoff);

        assertThat(snap.getSnapshotId()).isEqualTo("20250731-001");
        assertThat(snap.getCutoffTime()).isEqualTo(cutoff);
        assertThat(snap.getHash()).hasSize(64); // SHA-256 hex = 64 chars
        assertThat(snap.getRowCounts()).contains("\"market_data\":10000");
        assertThat(snap.getSourceVersions()).contains("\"derived_local\":7000");
        assertThat(snap.getQualityStatus()).contains("\"cutoff\":\"2025-07-31\"");
        verify(dataSnapshotRepository).save(any(DataSnapshot.class));
    }

    @Test
    void createSnapshotIncrementsSequenceForSameCutoff() {
        LocalDate cutoff = LocalDate.of(2025, 7, 31);
        when(marketDataRepository.count()).thenReturn(0L);
        when(marketPriceRawRepository.countAll()).thenReturn(0L);
        when(adjustFactorRepository.countAll()).thenReturn(0L);
        when(securityMasterHistoryRepository.count()).thenReturn(0L);
        when(tradingCalendarDayRepository.count()).thenReturn(0L);
        when(indexQuoteRepository.count()).thenReturn(0L);
        when(adjustFactorRepository.countBySource("derived_local")).thenReturn(0L);
        when(adjustFactorRepository.countBySource("official")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("current_snapshot")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("eastmoney")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("tushare")).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource("eastmoney_derived")).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource("tushare")).thenReturn(0L);
        when(indexQuoteRepository.countBySource("eastmoney")).thenReturn(0L);
        when(indexQuoteRepository.countBySource("tushare")).thenReturn(0L);
        when(marketDataRepository.findMinTradeDate()).thenReturn(java.util.Optional.empty());
        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.empty());
        when(marketPriceRawRepository.findMinTradeDate()).thenReturn(java.util.Optional.empty());
        when(marketPriceRawRepository.findMaxTradeDate()).thenReturn(java.util.Optional.empty());
        // 当日已有 2 条 → 序号 003
        when(dataSnapshotRepository.findBySnapshotIdStartingWith("20250731"))
                .thenReturn(List.of(new DataSnapshot(), new DataSnapshot()));

        DataSnapshot snap = service.createSnapshot(cutoff);

        assertThat(snap.getSnapshotId()).isEqualTo("20250731-003");
    }

    @Test
    void createSnapshotUsesMarketDataMaxWhenCutoffNull() {
        LocalDate max = LocalDate.of(2025, 6, 30);
        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(max));
        when(marketDataRepository.count()).thenReturn(0L);
        when(marketPriceRawRepository.countAll()).thenReturn(0L);
        when(adjustFactorRepository.countAll()).thenReturn(0L);
        when(securityMasterHistoryRepository.count()).thenReturn(0L);
        when(tradingCalendarDayRepository.count()).thenReturn(0L);
        when(indexQuoteRepository.count()).thenReturn(0L);
        when(adjustFactorRepository.countBySource("derived_local")).thenReturn(0L);
        when(adjustFactorRepository.countBySource("official")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("current_snapshot")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("eastmoney")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("tushare")).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource("eastmoney_derived")).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource("tushare")).thenReturn(0L);
        when(indexQuoteRepository.countBySource("eastmoney")).thenReturn(0L);
        when(indexQuoteRepository.countBySource("tushare")).thenReturn(0L);
        when(marketDataRepository.findMinTradeDate()).thenReturn(java.util.Optional.empty());
        when(marketPriceRawRepository.findMinTradeDate()).thenReturn(java.util.Optional.empty());
        when(marketPriceRawRepository.findMaxTradeDate()).thenReturn(java.util.Optional.empty());
        when(dataSnapshotRepository.findBySnapshotIdStartingWith("20250630")).thenReturn(List.of());

        DataSnapshot snap = service.createSnapshot(null);

        assertThat(snap.getCutoffTime()).isEqualTo(max);
        assertThat(snap.getSnapshotId()).startsWith("20250630-");
    }

    @Test
    void hashIsDeterministicForSameCounts() {
        // 同样的行数与 source 分布应产生相同 hash
        LocalDate cutoff = LocalDate.of(2025, 7, 31);
        setupStubsForDeterministicHash(cutoff);
        when(dataSnapshotRepository.findBySnapshotIdStartingWith("20250731")).thenReturn(List.of());

        DataSnapshot s1 = service.createSnapshot(cutoff);

        // 重置序号 mock
        when(dataSnapshotRepository.findBySnapshotIdStartingWith("20250731")).thenReturn(List.of(new DataSnapshot()));
        setupStubsForDeterministicHash(cutoff);

        DataSnapshot s2 = service.createSnapshot(cutoff);

        assertThat(s1.getHash()).isEqualTo(s2.getHash());
    }

    private void setupStubsForDeterministicHash(LocalDate cutoff) {
        when(marketDataRepository.count()).thenReturn(100L);
        when(marketPriceRawRepository.countAll()).thenReturn(50L);
        when(adjustFactorRepository.countAll()).thenReturn(40L);
        when(securityMasterHistoryRepository.count()).thenReturn(30L);
        when(tradingCalendarDayRepository.count()).thenReturn(20L);
        when(indexQuoteRepository.count()).thenReturn(10L);
        when(adjustFactorRepository.countBySource("derived_local")).thenReturn(40L);
        when(adjustFactorRepository.countBySource("official")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("current_snapshot")).thenReturn(30L);
        when(securityMasterHistoryRepository.countBySource("eastmoney")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("tushare")).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource("eastmoney_derived")).thenReturn(20L);
        when(tradingCalendarDayRepository.countBySource("tushare")).thenReturn(0L);
        when(indexQuoteRepository.countBySource("eastmoney")).thenReturn(10L);
        when(indexQuoteRepository.countBySource("tushare")).thenReturn(0L);
        when(marketDataRepository.findMinTradeDate()).thenReturn(java.util.Optional.of(cutoff.minusDays(1)));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(cutoff));
        when(marketPriceRawRepository.findMinTradeDate()).thenReturn(java.util.Optional.of(cutoff.minusDays(1)));
        when(marketPriceRawRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(cutoff));
    }
}
