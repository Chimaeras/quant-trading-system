package com.quant.service;

import com.quant.entity.DataSnapshot;
import com.quant.exception.BusinessException;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.DataSnapshotRepository;
import com.quant.repository.LabelSnapshotRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.TradingCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 数据质量硬门禁（MP-023 / MP-006）：按用途评估数据底座是否允许下游任务运行。
 *
 * <p>MP-006：门禁从「查询页面」升级为领域服务不可绕过的前置凭证。
 * <ul>
 *   <li>支持 {@link GateRequest}：purpose + snapshot + 可选区间/标签版本/策略/horizon；</li>
 *   <li>按请求区间做逐日/区间覆盖率检查（raw、factor、calendar、PIT、label）；</li>
 *   <li>每次评估产出确定性 {@code decisionId}（供产物 lineage 持久化，可追溯）；</li>
 *   <li>仅 {@code RESEARCH_EXPLORATORY} 可显式 DEGRADED 运行，概率训练/晋级/发布要求 PASS。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DataQualityGateService {

    public static final String THRESHOLD_VERSION = "gate_v2";

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_BLOCKED = "BLOCKED";

    public static final String PURPOSE_RULE_SCORE = "RULE_SCORE";
    public static final String PURPOSE_PROBABILITY_TRAIN = "PROBABILITY_TRAIN";
    public static final String PURPOSE_PROBABILITY_PROMOTE = "PROBABILITY_PROMOTE";
    public static final String PURPOSE_DAILY_PUBLISH = "DAILY_PUBLISH";
    /** 仅研究探索：允许 DEGRADED 运行，但产物永不允许晋级（MP-006）。 */
    public static final String PURPOSE_RESEARCH_EXPLORATORY = "RESEARCH_EXPLORATORY";

    private static final int CALENDAR_MISSING_DEGRADED = 5;
    private static final int CALENDAR_MISSING_BLOCKED = 20;
    /** 区间覆盖率抽样上限：超过该天数的区间，逐日检查改为均匀抽样。 */
    private static final int RANGE_SAMPLE_MAX_DAYS = 40;
    private static final double RANGE_COVERAGE_DEGRADED = 0.9;
    private static final double RANGE_COVERAGE_BLOCKED = 0.5;

    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final AdjustFactorRepository adjustFactorRepository;
    private final SecurityMasterHistoryRepository securityMasterHistoryRepository;
    private final TradingCalendarDayRepository tradingCalendarDayRepository;
    private final LabelSnapshotRepository labelSnapshotRepository;
    private final TradingCalendarService tradingCalendarService;
    private final DataSnapshotRepository dataSnapshotRepository;

    /** 门禁请求：purpose + snapshot + 可选区间/标签/策略/horizon。 */
    public record GateRequest(
            String purpose,
            String snapshotId,
            LocalDate startDate,
            LocalDate endDate,
            String labelVersion,
            String strategyType,
            Integer horizon
    ) {
        public static GateRequest of(String purpose, String snapshotId) {
            return new GateRequest(purpose, snapshotId, null, null, null, null, null);
        }

        public static GateRequest ofRange(String purpose, String snapshotId, LocalDate start, LocalDate end,
                                          String labelVersion, String strategyType, Integer horizon) {
            return new GateRequest(purpose, snapshotId, start, end, labelVersion, strategyType, horizon);
        }
    }

    /** 门禁评估结果：status + checks + decisionId + lineage。 */
    public record GateDecision(String status, String decisionId, String checkedSnapshotId,
                               Map<String, Object> raw, List<Map<String, Object>> checks) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(String purpose, String snapshotId) {
        return evaluate(GateRequest.of(purpose, snapshotId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> evaluate(GateRequest req) {
        String normalizedPurpose = normalizePurpose(req.purpose());
        String resolvedSnapshotId = resolveSnapshotId(req.snapshotId());

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(checkSnapshot(resolvedSnapshotId, normalizedPurpose));
        checks.add(checkTradingCalendar());
        checks.add(checkRawAndQfq(normalizedPurpose));
        checks.add(checkSecurityMaster(normalizedPurpose));
        if (req.startDate() != null && req.endDate() != null) {
            checks.addAll(checkRangeCoverage(req, resolvedSnapshotId, normalizedPurpose));
        }

        String gateStatus = aggregateStatus(checks);
        String decisionId = computeDecisionId(req, gateStatus, checks);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate_status", gateStatus);
        out.put("purpose", normalizedPurpose);
        out.put("checks", checks);
        out.put("threshold_version", THRESHOLD_VERSION);
        out.put("checked_snapshot_id", resolvedSnapshotId != null ? resolvedSnapshotId : "");
        out.put("decision_id", decisionId);
        if (req.startDate() != null) {
            out.put("check_start", req.startDate().toString());
            out.put("check_end", req.endDate().toString());
        }
        if (req.labelVersion() != null && !req.labelVersion().isBlank()) {
            out.put("check_label_version", req.labelVersion().trim());
        }
        return out;
    }

    /**
     * 断言门禁允许（fail-closed）：BLOCKED 一律拒绝；概率训练/晋级/发布要求 PASS，DEGRADED 拒绝；
     * 规则打分与研究探索允许 DEGRADED 降级运行。
     *
     * @return 完整门禁决策（含 decisionId），调用方须写入产物 lineage。
     */
    public GateDecision assertAllowed(GateRequest req) {
        Map<String, Object> gate = evaluate(req);
        String status = String.valueOf(gate.get("gate_status"));
        if (STATUS_BLOCKED.equals(status)) {
            throw new BusinessException("数据质量门禁 BLOCKED（purpose=" + req.purpose() + "）: " + summarizeChecks(gate));
        }
        if (requiresPass(req.purpose()) && STATUS_DEGRADED.equals(status)) {
            throw new BusinessException("数据质量门禁 DEGRADED，purpose " + req.purpose() + " 要求 PASS: "
                    + summarizeChecks(gate));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) gate.get("checks");
        return new GateDecision(
                status,
                String.valueOf(gate.get("decision_id")),
                gate.get("checked_snapshot_id") instanceof String s ? s : null,
                gate,
                checks != null ? checks : List.of());
    }

    private Map<String, Object> checkSnapshot(String snapshotId, String purpose) {
        Map<String, Object> check = baseCheck("snapshot_exists");
        if (snapshotId == null || snapshotId.isBlank()) {
            if (PURPOSE_DAILY_PUBLISH.equals(purpose)) {
                long total = dataSnapshotRepository.count();
                check.put("status", total > 0 ? STATUS_PASS : STATUS_BLOCKED);
                check.put("detail", total > 0 ? "未指定 snapshotId，存在可用快照" : "DAILY_PUBLISH 要求至少一条数据快照");
            } else if (PURPOSE_PROBABILITY_TRAIN.equals(purpose)
                    || PURPOSE_PROBABILITY_PROMOTE.equals(purpose)) {
                check.put("status", STATUS_BLOCKED);
                check.put("detail", "概率任务要求显式 snapshotId（MP-002：禁止把空 snapshot 自动替换成「最新」）");
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
            DataSnapshot s = snaps.get(0);
            check.put("status", STATUS_PASS);
            check.put("detail", "快照存在: " + snapshotId + "（quality=" + s.getQualityStatus()
                    + ", cutoff=" + (s.getCutoffTime() != null ? s.getCutoffTime() : "") + "）");
            check.put("quality_status", s.getQualityStatus());
            check.put("cutoff_time", s.getCutoffTime() != null ? s.getCutoffTime().toString() : null);
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

    /**
     * 按请求区间做覆盖率检查（MP-006）：raw/factor/PIT/label 逐日（或抽样）覆盖。
     * 区间上限 {@link #RANGE_SAMPLE_MAX_DAYS} 天内逐日检查，更长区间均匀抽样，避免大区间全量扫描。
     */
    private List<Map<String, Object>> checkRangeCoverage(GateRequest req, String snapshotId, String purpose) {
        List<Map<String, Object>> checks = new ArrayList<>();
        LocalDate start = req.startDate();
        LocalDate end = req.endDate();
        if (start.isAfter(end)) {
            Map<String, Object> c = baseCheck("range_validity");
            c.put("status", STATUS_BLOCKED);
            c.put("detail", "区间非法: startDate(" + start + ") 晚于 endDate(" + end + ")");
            checks.add(c);
            return checks;
        }

        List<LocalDate> calendarDays = tradingCalendarDayRepository.findTradeDatesBetween(start, end);
        checks.add(checkRangeRawFactor(req, calendarDays, purpose));
        checks.add(checkRangePit(req, calendarDays, purpose));
        if (req.labelVersion() != null && !req.labelVersion().isBlank()) {
            checks.add(checkRangeLabel(req, purpose));
        }
        return checks;
    }

    private Map<String, Object> checkRangeRawFactor(GateRequest req, List<LocalDate> calendarDays, String purpose) {
        Map<String, Object> check = baseCheck("range_raw_factor_coverage");
        LocalDate start = req.startDate();
        LocalDate end = req.endDate();
        long rawDays = marketPriceRawRepository.countDistinctTradeDatesBetween(start, end);
        long factorDays = adjustFactorRepository.countDistinctTradeDatesBetween(start, end);
        long rawStocks = marketPriceRawRepository.countDistinctStocksBetween(start, end);
        long factorStocks = adjustFactorRepository.countDistinctStocksBetween(start, end);
        int calendarSize = calendarDays.size();
        check.put("calendar_days", calendarSize);
        check.put("raw_days", rawDays);
        check.put("factor_days", factorDays);
        check.put("raw_stocks", rawStocks);
        check.put("factor_stocks", factorStocks);

        boolean probabilityPurpose = isProbabilityPurpose(purpose);
        if (calendarSize == 0) {
            check.put("status", STATUS_DEGRADED);
            check.put("detail", "区间内无交易日历，无法逐日比对（raw_days=" + rawDays + ", factor_days=" + factorDays + "）");
            return check;
        }
        double rawRatio = rawDays / (double) calendarSize;
        double factorRatio = factorDays / (double) calendarSize;
        check.put("raw_ratio", round2(rawRatio));
        check.put("factor_ratio", round2(factorRatio));
        if (rawRatio < RANGE_COVERAGE_BLOCKED || factorRatio < RANGE_COVERAGE_BLOCKED) {
            check.put("status", STATUS_BLOCKED);
            check.put("detail", "区间内 raw/factor 覆盖严重不足（raw=" + round2(rawRatio) + ", factor=" + round2(factorRatio)
                    + "，阈值 " + RANGE_COVERAGE_BLOCKED + "）");
        } else if (rawRatio < RANGE_COVERAGE_DEGRADED || factorRatio < RANGE_COVERAGE_DEGRADED
                || (probabilityPurpose && (rawStocks == 0 || factorStocks == 0))) {
            check.put("status", probabilityPurpose ? STATUS_BLOCKED : STATUS_DEGRADED);
            check.put("detail", "区间内 raw/factor 覆盖率不足（raw=" + round2(rawRatio) + ", factor=" + round2(factorRatio)
                    + "，阈值 " + RANGE_COVERAGE_DEGRADED + "）");
        } else {
            check.put("status", STATUS_PASS);
            check.put("detail", "区间内 raw/factor 覆盖达标（raw=" + round2(rawRatio) + ", factor=" + round2(factorRatio) + "）");
        }
        return check;
    }

    private Map<String, Object> checkRangePit(GateRequest req, List<LocalDate> calendarDays, String purpose) {
        Map<String, Object> check = baseCheck("range_pit_coverage");
        List<LocalDate> sampled = sampleDays(calendarDays);
        if (sampled.isEmpty()) {
            check.put("status", STATUS_DEGRADED);
            check.put("detail", "区间内无交易日，无法评估 PIT 覆盖");
            return check;
        }
        int effectiveRows = 0;
        int totalRows = 0;
        for (LocalDate day : sampled) {
            int n = securityMasterHistoryRepository.findAllEffectiveAt(day).size();
            totalRows += n;
            effectiveRows += n;
        }
        int expectedPerDay = totalRows / sampled.size();
        check.put("sampled_days", sampled.size());
        check.put("avg_effective_per_day", expectedPerDay);
        if (expectedPerDay == 0) {
            check.put("status", isProbabilityPurpose(purpose) ? STATUS_BLOCKED : STATUS_DEGRADED);
            check.put("detail", "区间内 PIT 主数据有效记录为 0（抽样 " + sampled.size() + " 日）");
        } else {
            check.put("status", STATUS_PASS);
            check.put("detail", "区间内 PIT 主数据平均 " + expectedPerDay + " 条/日（抽样 " + sampled.size() + " 日）");
        }
        return check;
    }

    private Map<String, Object> checkRangeLabel(GateRequest req, String purpose) {
        Map<String, Object> check = baseCheck("range_label_coverage");
        String version = req.labelVersion().trim();
        long labels = labelSnapshotRepository.countByVersion(version);
        long min = labelSnapshotRepository.countByVersionAndExecStatus(version, "FILLED");
        check.put("label_version", version);
        check.put("label_rows", labels);
        check.put("filled_rows", min);
        if (labels == 0) {
            check.put("status", isProbabilityPurpose(purpose) ? STATUS_BLOCKED : STATUS_DEGRADED);
            check.put("detail", "标签版本 " + version + " 无任何记录");
            return check;
        }
        // 区间内是否有信号日标签（粗略判断）：min/max signal date 与请求区间相交
        LocalDate minDate = labelSnapshotRepository.findMinSignalDate(version).orElse(null);
        LocalDate maxDate = labelSnapshotRepository.findMaxSignalDate(version).orElse(null);
        check.put("signal_date_min", minDate != null ? minDate.toString() : null);
        check.put("signal_date_max", maxDate != null ? maxDate.toString() : null);
        boolean intersects = minDate != null && maxDate != null
                && !maxDate.isBefore(req.startDate()) && !minDate.isAfter(req.endDate());
        if (!intersects) {
            check.put("status", isProbabilityPurpose(purpose) ? STATUS_BLOCKED : STATUS_DEGRADED);
            check.put("detail", "标签版本 " + version + " 信号日区间 [" + minDate + "," + maxDate
                    + "] 与请求区间 [" + req.startDate() + "," + req.endDate() + "] 无交集");
        } else {
            check.put("status", STATUS_PASS);
            check.put("detail", "标签版本 " + version + " 覆盖请求区间（signal_date "
                    + minDate + "~" + maxDate + "）");
        }
        return check;
    }

    /** 超过 {@link #RANGE_SAMPLE_MAX_DAYS} 天时均匀抽样，避免大区间全量扫描。 */
    private static List<LocalDate> sampleDays(List<LocalDate> days) {
        if (days.size() <= RANGE_SAMPLE_MAX_DAYS) {
            return days;
        }
        int step = (int) Math.ceil(days.size() / (double) RANGE_SAMPLE_MAX_DAYS);
        List<LocalDate> sampled = new ArrayList<>();
        for (int i = 0; i < days.size(); i += step) {
            sampled.add(days.get(i));
        }
        if (sampled.size() < 2) {
            sampled.add(days.get(days.size() - 1));
        }
        return sampled;
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
        return dataSnapshotRepository.findLatest(PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(DataSnapshot::getSnapshotId)
                .orElse(null);
    }

    /** 确定性 decisionId：由 purpose/snapshot/区间/标签/结果状态与各 check 状态哈希而来，可复现可追溯。 */
    private static String computeDecisionId(GateRequest req, String status, List<Map<String, Object>> checks) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(normalizePurpose(req.purpose())).append('|');
            sb.append(req.snapshotId() != null ? req.snapshotId().trim() : "-").append('|');
            sb.append(req.startDate() != null ? req.startDate() : "-").append('|');
            sb.append(req.endDate() != null ? req.endDate() : "-").append('|');
            sb.append(req.labelVersion() != null ? req.labelVersion().trim() : "-").append('|');
            sb.append(status).append('|');
            for (Map<String, Object> c : checks) {
                sb.append(c.get("name")).append('=').append(c.get("status")).append(';');
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "GATE_" + HexFormat.of().formatHex(dig, 0, 16).toUpperCase();
        } catch (Exception e) {
            return "GATE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        }
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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
