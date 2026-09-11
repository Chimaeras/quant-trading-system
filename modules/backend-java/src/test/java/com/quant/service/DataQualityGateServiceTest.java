package com.quant.service;

import com.quant.entity.DataSnapshot;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.DataSnapshotRepository;
import com.quant.repository.LabelSnapshotRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.TradingCalendarDayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DataQualityGateService 回归测试：MP-002 概率任务的快照门禁必须 fail-closed。
 *
 * <p>关键点：{@code resolveSnapshotId} 会把「空 snapshotId」自动解析为库中最新快照（供
 * lineage 展示），但 {@code checkSnapshot} 的 fail-closed 分支只认「原始入参为空」。
 * 因此两者不能共用同一个变量，否则概率任务的快照要求会被静默替换成「最新」而永久失效。
 */
class DataQualityGateServiceTest {

    private DataSnapshotRepository dataSnapshotRepository;
    private DataQualityGateService service;

    @BeforeEach
    void setUp() {
        dataSnapshotRepository = mock(DataSnapshotRepository.class);
        service = new DataQualityGateService(
                mock(MarketDataRepository.class),
                mock(MarketPriceRawRepository.class),
                mock(AdjustFactorRepository.class),
                mock(SecurityMasterHistoryRepository.class),
                mock(TradingCalendarDayRepository.class),
                mock(LabelSnapshotRepository.class),
                mock(TradingCalendarService.class),
                dataSnapshotRepository
        );
    }

    @Test
    void probabilityTrainWithoutSnapshotIdIsBlockedNotSilentlyResolved() {
        when(dataSnapshotRepository.findLatest(any(Pageable.class))).thenReturn(List.of(snapshot("SNAP_LATEST", "OK")));

        Map<String, Object> gate = service.evaluate(DataQualityGateService.PURPOSE_PROBABILITY_TRAIN, null);

        Map<String, Object> check = checkNamed(gate, "snapshot_exists");
        assertThat(check.get("status")).isEqualTo(DataQualityGateService.STATUS_BLOCKED);
        assertThat(String.valueOf(check.get("detail"))).contains("MP-002");
        assertThat(gate.get("gate_status")).isEqualTo(DataQualityGateService.STATUS_BLOCKED);
        // 解析出的快照仍如实披露，供调用方写 lineage
        assertThat(gate.get("checked_snapshot_id")).isEqualTo("SNAP_LATEST");
    }

    @Test
    void explicitSnapshotIdPassesSnapshotCheck() {
        when(dataSnapshotRepository.findBySnapshotId("SNAP_1")).thenReturn(List.of(snapshot("SNAP_1", "OK")));

        Map<String, Object> gate = service.evaluate(DataQualityGateService.PURPOSE_PROBABILITY_TRAIN, "SNAP_1");

        Map<String, Object> check = checkNamed(gate, "snapshot_exists");
        assertThat(check.get("status")).isEqualTo(DataQualityGateService.STATUS_PASS);
        assertThat(String.valueOf(check.get("detail"))).contains("SNAP_1");
    }

    @Test
    void missingExplicitSnapshotIsBlocked() {
        when(dataSnapshotRepository.findBySnapshotId("SNAP_GONE")).thenReturn(List.of());

        Map<String, Object> gate = service.evaluate(DataQualityGateService.PURPOSE_PROBABILITY_TRAIN, "SNAP_GONE");

        Map<String, Object> check = checkNamed(gate, "snapshot_exists");
        assertThat(check.get("status")).isEqualTo(DataQualityGateService.STATUS_BLOCKED);
        assertThat(String.valueOf(check.get("detail"))).contains("快照不存在");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkNamed(Map<String, Object> gate, String name) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) gate.get("checks");
        return checks.stream()
                .filter(c -> name.equals(c.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 check: " + name));
    }

    private static DataSnapshot snapshot(String snapshotId, String qualityStatus) {
        DataSnapshot s = new DataSnapshot();
        s.setSnapshotId(snapshotId);
        s.setQualityStatus(qualityStatus);
        return s;
    }
}
