package com.quant.service.scoring;

import com.quant.entity.StockFinancialSnapshot;
import com.quant.repository.StockFinancialSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinancialSnapshotLoaderTest {

    @Test
    void excludesFinancialReportPublishedAfterScoringDate() {
        StockFinancialSnapshotRepository repository = mock(StockFinancialSnapshotRepository.class);
        FinancialSnapshotLoader loader = new FinancialSnapshotLoader(repository);

        StockFinancialSnapshot known = snapshot(
                LocalDate.of(2024, 12, 31),
                LocalDate.of(2025, 3, 20)
        );
        StockFinancialSnapshot future = snapshot(
                LocalDate.of(2025, 3, 31),
                LocalDate.of(2025, 4, 30)
        );
        when(repository.findByAnnouncementDateLessThanEqualOrAnnouncementDateIsNull(LocalDate.of(2025, 4, 1)))
                .thenReturn(List.of(known, future));

        Map<String, StockFinancialSnapshot> result =
                loader.loadLatestByStockKey(LocalDate.of(2025, 4, 1));

        assertThat(result).containsEntry("000001|SZSE", known);
    }

    @Test
    void usesConservativeAvailabilityForLegacyRowsWithoutAnnouncementDate() {
        StockFinancialSnapshotRepository repository = mock(StockFinancialSnapshotRepository.class);
        FinancialSnapshotLoader loader = new FinancialSnapshotLoader(repository);
        StockFinancialSnapshot legacy = snapshot(LocalDate.of(2024, 12, 31), null);
        when(repository.findByAnnouncementDateLessThanEqualOrAnnouncementDateIsNull(LocalDate.of(2025, 4, 29)))
                .thenReturn(List.of(legacy));
        when(repository.findByAnnouncementDateLessThanEqualOrAnnouncementDateIsNull(LocalDate.of(2025, 4, 30)))
                .thenReturn(List.of(legacy));

        assertThat(loader.loadLatestByStockKey(LocalDate.of(2025, 4, 29))).isEmpty();
        assertThat(loader.loadLatestByStockKey(LocalDate.of(2025, 4, 30)))
                .containsEntry("000001|SZSE", legacy);
    }

    private StockFinancialSnapshot snapshot(LocalDate reportPeriod, LocalDate announcementDate) {
        StockFinancialSnapshot snapshot = new StockFinancialSnapshot();
        snapshot.setStockCode("000001");
        snapshot.setExchangeType("SZSE");
        snapshot.setReportPeriod(reportPeriod);
        snapshot.setAnnouncementDate(announcementDate);
        return snapshot;
    }
}
