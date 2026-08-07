package com.quant.service;

import com.quant.entity.DataSnapshot;
import com.quant.exception.BusinessException;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.DataSnapshotRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.TradingCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据质量硬门禁（MP-023）：按用途评估数据底座是否允许下游任务运行。
 */
@Service
@RequiredArgsConstructor
public class DataQualityGateService {

    public static final String THRESHOLD_VERSION = "gate_v1";

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_BLOCKED = "BLOCKED";

    public static final String PURPOSE_RULE_SCORE = "RULE_SCORE";
    public static final String PURPOSE_PROBABILITY_TRAIN = "PROBABILITY_TRAIN";
    public static final String PURPOSE_PROBABILITY_PROMOTE = "PROBABILITY_PROMOTE";
    public static final String PURPOSE_DAILY_PUBLISH = "DAILY_PUBLISH";

    private static final int CALENDAR_MISSING_DEGRADED = 5;
    private static final int CALENDAR_MISSING_BLOCKED = 20;

    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final AdjustFactorRepository adjustFactorRepository;
    private final SecurityMasterHistoryRepository securityMasterHistoryRepository;
    private final TradingCalendarDayRepository tradingCalendarDayRepository;
    private final TradingCalendarService tradingCalendarService;
    private final DataSnapshotRepository dataSnapshotRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(String purpose, String snapshotId) {
        String normalizedPurpose = normalizePurpose(purpose);
        String resolvedSnapshotId = resolveSnapshotId(snapshotId);

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(checkSnapshot(resolvedSnapshotId, normalizedPurpose));
        checks.add(checkTradingCalendar());
        checks.add(checkRawAndQfq(normalizedPurpose));
        checks.add(checkSecurityMaster(normalizedPurpose));

        String gateStatus = aggregateStatus(checks);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate_status", gateStatus);
        out.put("purpose", normalizedPurpose);
        out.put("checks", checks);
        out.put("threshold_version", THRESHOLD_VERSION);
        out.put("checked_snapshot_id", resolvedSnapshotId != null ? resolvedSnapshotId : "");
        return out;
    }

    public void assertAllowed(String purpose, String snapshotId) {
        Map<String, Object> gate = evaluate(purpose, snapshotId);
        String status = String.valueOf(gate.get("gate_status"));
        if (STATUS_BLOCKED.equals(status)) {
            throw new BusinessException("数据质量门禁 BLOCKED（purpose=" + purpose + "）: " + summarizeChecks(gate));
        }
        if (requiresPass(purpose) && STATUS_DEGRADED.equals(status)) {
            throw new BusinessException("数据质量门禁 DEGRADED，purpose " + purpose + " 要求 PASS: " + summarizeChecks(gate));
        }
    }

    private Map<String, Object> checkSnapshot(String snapshotId, String purpose) {
        Map<String, Object> check = baseCheck("snapshot_exists");
        if (snapshotId == null || snapshotId.isBlank()) {
            if (PURPOSE_DAILY_PUBLISH.equals(purpose)) {
                long total = dataSnapshotRepository.count();
                check.put("status", total > 0 ? STATUS_PASS : STATUS_BLOCKED);
                check.put("detail", total > 0 ? "未指定 snapshotId，存在可用快照" : "DAILY_PUBLISH 要求至少一条数据快照");
            } else {
                check.put("status", STATUS_PASS);
                check.put("detail", "未指定 snapshotId，跳过快照校验");
            }
            return check;
        }
        List<DataSnapshot> snaps = dataSnapshotRepository.findBySnapshotId(snapshotId);
        if (snaps.isEmpty()) {
            check.put("status", STATUS_BLOCKED);
            check.put("detail", "快照不存在: " + snapshotId);
        } else {
            check.put("status", STATUS_PASS);
            check.put("detail", "快照存在: " + snapshotId);
        }
        return check;
    }

    private Map<String, Object> checkTradingCalendar() {
        Map<String, Object> check = baseCheck("trading_calendar_coverage");
        long calendarRows = tradingCalendarDayRepository.count();
        if (calendarRows == 0) {
            check.put("status", STATUS_DEGRADED);
            check.put("detail", "交易日历未初始化，无法与 exchange calendar 比对");
            return check;
        }
        LocalDate mdMin = marketDataRepository.findMinTradeDate().orElse(null);
        LocalDate mdMax = marketDataRepository.findMaxTradeDate().orElse(null);
        if (mdMin == null || mdMax == null) {
            check.put("status", STATUS_BLOCKED);
            check.put("detail", "无行情数据，无法评估交易日历覆盖");
            return check;
        }
        List<LocalDate> calendarDays = tradingCalendarService.findTradingDays(mdMin, mdMax);
        List<LocalDate> mdDays = marketDataRepository.findDistinctTradeDatesBetween(mdMin, mdMax);
        Set<LocalDate> mdSet = new HashSet<>(mdDays);
        long missing = calendarDays.stream().filter(d -> !mdSet.contains(d)).count();
        check.put("calendar_days", calendarDays.size());
        check.put("market_data_days", mdDays.size());
        check.put("missing_in_market_data", missing);
        if (missing >= CALENDAR_MISSING_BLOCKED) {
            check.put("status", STATUS_BLOCKED);
            check.put("detail", "交易日历覆盖缺口过大: " + missing + " 个交易日无行情");
        } else if (missing >= CALENDAR_MISSING_DEGRADED) {
            check.put("status", STATUS_DEGRADED);
            check.put("detail", "交易日历覆盖存在缺口: " + missing + " 个交易日无行情");
        } else {
            check.put("status", STATUS_PASS);
            check.put("detail", "交易日历与行情覆盖一致（缺口 " + missing + " 日）");
        }
        return check;
    }

    private Map<String, Object> checkRawAndQfq(String purpose) {
        Map<String, Object> check = baseCheck("raw_qfq_presence");
        long rawRows = marketPriceRawRepository.countAll();
        long factorRows = adjustFactorRepository.countAll();
        check.put("raw_rows", rawRows);
        check.put("adjust_factor_rows", factorRows);
        boolean probabilityPurpose = isProbabilityPurpose(purpose);
        if (rawRows == 0 || factorRows == 0) {
            check.put("status", probabilityPurpose ? STATUS_BLOCKED : STATUS_DEGRADED);
            check.put("detail", "不复权行情或复权因子未就绪（raw=" + rawRows + ", factor=" + factorRows + "）");
        } else {
            check.put("status", STATUS_PASS);
            check.put("detail", "不复权行情与复权因子均已启用");
        }
        return check;
    }

    private Map<String, Object> checkSecurityMaster(String purpose) {
        Map<String, Object> check = baseCheck("security_master_reliability");
        long rows = securityMasterHistoryRepository.count();
        long limited = securityMasterHistoryRepository.countByReliability("limited");
        check.put("total_rows", rows);
        check.put("limited_rows", limited);
        if (rows == 0) {
            check.put("status", STATUS_BLOCKED);
            check.put("detail", "证券主数据未初始化");
            return check;
        }
        boolean allLimited = limited >= rows;
        if (allLimited) {
            if (isProbabilityPurpose(purpose)) {
                check.put("status", STATUS_BLOCKED);
                check.put("detail", "证券主数据可靠度均为 limited，概率任务禁止");
            } else {
                check.put("status", STATUS_DEGRADED);
                check.put("detail", "证券主数据可靠度 limited，规则打分降级运行");
            }
        } else {
            check.put("status", STATUS_PASS);
            check.put("detail", "证券主数据已启用");
        }
        return check;
    }

    private static Map<String, Object> baseCheck(String name) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", name);
        return check;
    }

    private static String aggregateStatus(List<Map<String, Object>> checks) {
        boolean blocked = checks.stream().anyMatch(c -> STATUS_BLOCKED.equals(c.get("status")));
        if (blocked) {
            return STATUS_BLOCKED;
        }
        boolean degraded = checks.stream().anyMatch(c -> STATUS_DEGRADED.equals(c.get("status")));
        if (degraded) {
            return STATUS_DEGRADED;
        }
        return STATUS_PASS;
    }

    private static boolean requiresPass(String purpose) {
        String p = normalizePurpose(purpose);
        return PURPOSE_PROBABILITY_TRAIN.equals(p)
                || PURPOSE_PROBABILITY_PROMOTE.equals(p)
                || PURPOSE_DAILY_PUBLISH.equals(p);
    }

    private static boolean isProbabilityPurpose(String purpose) {
        String p = normalizePurpose(purpose);
        return PURPOSE_PROBABILITY_TRAIN.equals(p) || PURPOSE_PROBABILITY_PROMOTE.equals(p);
    }

    private static String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return PURPOSE_RULE_SCORE;
        }
        return purpose.trim().toUpperCase();
    }

    private String resolveSnapshotId(String snapshotId) {
        if (snapshotId != null && !snapshotId.isBlank()) {
            return snapshotId.trim();
        }
        return dataSnapshotRepository.findLatest(org.springframework.data.domain.PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(DataSnapshot::getSnapshotId)
                .orElse(null);
    }

    private static String summarizeChecks(Map<String, Object> gate) {
        Object checksObj = gate.get("checks");
        if (!(checksObj instanceof List<?> checks)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : checks) {
            if (item instanceof Map<?, ?> check) {
                String status = String.valueOf(check.get("status"));
                if (!STATUS_PASS.equals(status)) {
                    if (!sb.isEmpty()) {
                        sb.append("; ");
                    }
                    sb.append(check.get("name")).append(": ").append(check.get("detail"));
                }
            }
        }
        return sb.isEmpty() ? "未知原因" : sb.toString();
    }
}
