package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.DataSnapshot;
import com.quant.entity.DataSnapshotPartitionManifest;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.DataSnapshotPartitionManifestRepository;
import com.quant.repository.DataSnapshotRepository;
import com.quant.repository.IndexQuoteRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.TradingCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据快照服务（QP-006 / MP-004）。
 *
 * <p>记录某一 cutoff 时点的数据底座状态（各表行数、source 版本、质量状态、内容指纹），
 * 供历史重放与实验复现。新快照为内容不可变快照：hash 含各表稳定聚合指纹；
 * 只 insert 新行，从不 update 已有 snapshot / partition manifest。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSnapshotService {

    private final DataSnapshotRepository dataSnapshotRepository;
    private final DataSnapshotPartitionManifestRepository partitionManifestRepository;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final AdjustFactorRepository adjustFactorRepository;
    private final SecurityMasterHistoryRepository securityMasterHistoryRepository;
    private final TradingCalendarDayRepository tradingCalendarDayRepository;
    private final IndexQuoteRepository indexQuoteRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建内容不可变数据快照（只 insert，不更新旧行）。
     *
     * @param cutoffTime 数据截止时点（null 时取 market_data 最新交易日）。
     * @return 新插入的 DataSnapshot。
     */
    @Transactional
    public DataSnapshot createSnapshot(LocalDate cutoffTime) {
        long startMs = System.currentTimeMillis();
        if (cutoffTime == null) {
            cutoffTime = marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());
        }

        Map<String, Object> rowCounts = new LinkedHashMap<>();
        rowCounts.put("market_data", marketDataRepository.count());
        rowCounts.put("market_price_raw", marketPriceRawRepository.countAll());
        rowCounts.put("adjust_factor", adjustFactorRepository.countAll());
        rowCounts.put("security_master_history", securityMasterHistoryRepository.count());
        rowCounts.put("trading_calendar", tradingCalendarDayRepository.count());
        rowCounts.put("index_quote", indexQuoteRepository.count());

        Map<String, Object> sourceVersions = new LinkedHashMap<>();
        Map<String, Object> rawSource = new LinkedHashMap<>();
        rawSource.put("eastmoney_fqt0", marketPriceRawRepository.countAll());
        sourceVersions.put("market_price_raw", rawSource);

        Map<String, Object> factorSource = new LinkedHashMap<>();
        factorSource.put("derived_local", adjustFactorRepository.countBySource("derived_local"));
        factorSource.put("official", adjustFactorRepository.countBySource("official"));
        sourceVersions.put("adjust_factor", factorSource);

        Map<String, Object> smSource = new LinkedHashMap<>();
        smSource.put("current_snapshot", securityMasterHistoryRepository.countBySource("current_snapshot"));
        smSource.put("eastmoney", securityMasterHistoryRepository.countBySource("eastmoney"));
        smSource.put("tushare", securityMasterHistoryRepository.countBySource("tushare"));
        sourceVersions.put("security_master_history", smSource);

        Map<String, Object> tcSource = new LinkedHashMap<>();
        tcSource.put("eastmoney_derived", tradingCalendarDayRepository.countBySource("eastmoney_derived"));
        tcSource.put("tushare", tradingCalendarDayRepository.countBySource("tushare"));
        sourceVersions.put("trading_calendar", tcSource);

        Map<String, Object> idxSource = new LinkedHashMap<>();
        idxSource.put("eastmoney", indexQuoteRepository.countBySource("eastmoney"));
        idxSource.put("tushare", indexQuoteRepository.countBySource("tushare"));
        sourceVersions.put("index_quote", idxSource);

        // 各表内容指纹
        List<PartitionFp> partitions = new ArrayList<>();
        partitions.add(buildPriceTableFp("market_price_raw",
                firstRow(marketPriceRawRepository.aggregateContentFingerprint()), true));
        partitions.add(buildPriceTableFp("market_data",
                firstRow(marketDataRepository.aggregateContentFingerprint()), true));
        partitions.add(buildAdjustFp(firstRow(adjustFactorRepository.aggregateContentFingerprint())));
        partitions.add(buildMasterFp(firstRow(securityMasterHistoryRepository.aggregateContentFingerprint())));
        partitions.add(buildCalendarFp(firstRow(tradingCalendarDayRepository.aggregateContentFingerprint())));
        partitions.add(buildIndexFp(firstRow(indexQuoteRepository.aggregateContentFingerprint())));

        Map<String, Object> partitionManifestMap = new LinkedHashMap<>();
        for (PartitionFp p : partitions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("row_count", p.rowCount);
            entry.put("min_trade_date", p.minTradeDate != null ? p.minTradeDate.toString() : "");
            entry.put("max_trade_date", p.maxTradeDate != null ? p.maxTradeDate.toString() : "");
            entry.put("fingerprint", p.fingerprint);
            entry.put("content_hash", p.contentHash);
            partitionManifestMap.put(p.tableName, entry);
        }
        String partitionManifestJson = toJson(partitionManifestMap);
        String manifestHash = sha256(partitionManifestJson);

        String qualityGate = resolveQualityGate(rowCounts);
        Map<String, Object> qualityStatus = new LinkedHashMap<>();
        qualityStatus.put("cutoff", cutoffTime.toString());
        qualityStatus.put("quality_gate", qualityGate);
        qualityStatus.put("market_data_min", marketDataRepository.findMinTradeDate().map(LocalDate::toString).orElse(""));
        qualityStatus.put("market_data_max", marketDataRepository.findMaxTradeDate().map(LocalDate::toString).orElse(""));
        qualityStatus.put("raw_min", marketPriceRawRepository.findMinTradeDate().map(LocalDate::toString).orElse(""));
        qualityStatus.put("raw_max", marketPriceRawRepository.findMaxTradeDate().map(LocalDate::toString).orElse(""));

        String hash = computeHash(rowCounts, sourceVersions, partitionManifestMap);

        // V2-MP-030: 同 cutoff 日 + 同内容 hash → 幂等返回既有快照，不重复创建
        var existing = dataSnapshotRepository.findFirstByCutoffTimeAndHash(cutoffTime, hash);
        if (existing.isPresent()) {
            log.info("数据快照幂等返回: snapshotId={}, cutoff={}, hash={}",
                    existing.get().getSnapshotId(), cutoffTime, hash);
            return existing.get();
        }

        String snapshotId = generateSnapshotId(cutoffTime);

        DataSnapshot snap = new DataSnapshot();
        snap.setSnapshotId(snapshotId);
        snap.setCutoffTime(cutoffTime);
        snap.setRowCounts(toJson(rowCounts));
        snap.setSourceVersions(toJson(sourceVersions));
        snap.setQualityStatus(toJson(qualityStatus));
        snap.setPartitionManifest(partitionManifestJson);
        snap.setMetadataOnly(false);
        snap.setManifestHash(manifestHash);
        snap.setHash(hash);
        snap.setCreatedBy("manual");
        // insert-only：从不 update 已有 snapshot
        dataSnapshotRepository.save(snap);

        List<DataSnapshotPartitionManifest> manifestRows = new ArrayList<>();
        for (PartitionFp p : partitions) {
            DataSnapshotPartitionManifest row = new DataSnapshotPartitionManifest();
            row.setSnapshotId(snapshotId);
            row.setTableName(p.tableName);
            row.setRowCount(p.rowCount);
            row.setMinTradeDate(p.minTradeDate);
            row.setMaxTradeDate(p.maxTradeDate);
            row.setFingerprint(p.fingerprint);
            row.setContentHash(p.contentHash);
            manifestRows.add(row);
        }
        partitionManifestRepository.saveAll(manifestRows);

        log.info("数据快照创建: snapshotId={}, cutoff={}, hash={}, manifestHash={}, partitions={}, elapsed_ms={}",
                snapshotId, cutoffTime, hash, manifestHash, partitions.size(), System.currentTimeMillis() - startMs);
        return snap;
    }

    /** 按 snapshotId 查找（取第一条）。 */
    @Transactional(readOnly = true)
    public Optional<DataSnapshot> findBySnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return Optional.empty();
        }
        List<DataSnapshot> list = dataSnapshotRepository.findBySnapshotId(snapshotId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** 要求快照存在，否则抛 IllegalArgumentException。 */
    @Transactional(readOnly = true)
    public DataSnapshot requireSnapshot(String id) {
        return findBySnapshotId(id)
                .orElseThrow(() -> new IllegalArgumentException("数据快照不存在: " + id));
    }

    /** 最近 N 条快照（降序）。 */
    @Transactional(readOnly = true)
    public List<DataSnapshot> listLatest(int limit) {
        return dataSnapshotRepository.findLatest(PageRequest.of(0, Math.max(1, limit)));
    }

    /** 全量快照计数（供覆盖率审计）。 */
    @Transactional(readOnly = true)
    public long count() {
        return dataSnapshotRepository.count();
    }

    /** 创建响应字段（供 Controller）。 */
    public Map<String, Object> toCreateResponse(DataSnapshot snap) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot_id", snap.getSnapshotId());
        out.put("cutoff", snap.getCutoffTime() != null ? snap.getCutoffTime().toString() : "");
        out.put("cutoff_time", snap.getCutoffTime() != null ? snap.getCutoffTime().toString() : "");
        out.put("hash", snap.getHash());
        out.put("manifest_hash", snap.getManifestHash());
        out.put("partition_count", countPartitions(snap));
        out.put("quality_gate", extractQualityGate(snap.getQualityStatus()));
        out.put("metadata_only", snap.getMetadataOnly() != null ? snap.getMetadataOnly() : true);
        out.put("row_counts", snap.getRowCounts());
        out.put("source_versions", snap.getSourceVersions());
        out.put("quality_status", snap.getQualityStatus());
        out.put("partition_manifest", snap.getPartitionManifest());
        out.put("created_by", snap.getCreatedBy());
        out.put("message", "快照已创建：" + snap.getSnapshotId());
        return out;
    }

    private int countPartitions(DataSnapshot snap) {
        if (snap.getSnapshotId() != null) {
            long n = partitionManifestRepository.countBySnapshotId(snap.getSnapshotId());
            if (n > 0) {
                return (int) n;
            }
        }
        if (snap.getPartitionManifest() == null || snap.getPartitionManifest().isBlank()) {
            return 0;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(snap.getPartitionManifest(), Map.class);
            return map.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private String extractQualityGate(String qualityStatusJson) {
        if (qualityStatusJson == null || qualityStatusJson.isBlank()) {
            return "unknown";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(qualityStatusJson, Map.class);
            Object g = map.get("quality_gate");
            return g != null ? g.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String resolveQualityGate(Map<String, Object> rowCounts) {
        long raw = ((Number) rowCounts.getOrDefault("market_price_raw", 0L)).longValue();
        long cal = ((Number) rowCounts.getOrDefault("trading_calendar", 0L)).longValue();
        long md = ((Number) rowCounts.getOrDefault("market_data", 0L)).longValue();
        if (raw > 0 && cal > 0 && md > 0) {
            return "ok";
        }
        if (md > 0 || raw > 0) {
            return "warn";
        }
        return "fail";
    }

    private String generateSnapshotId(LocalDate cutoff) {
        String prefix = cutoff.format(DateTimeFormatter.BASIC_ISO_DATE);
        long existing = dataSnapshotRepository.countBySnapshotIdStartingWith(prefix);
        return prefix + "-" + String.format("%03d", existing + 1);
    }

    private String computeHash(Map<String, Object> rowCounts,
                               Map<String, Object> sourceVersions,
                               Map<String, Object> partitionManifest) {
        String content = toJson(rowCounts) + "|" + toJson(sourceVersions) + "|" + toJson(partitionManifest);
        return sha256(content);
    }

    private static Object[] firstRow(List<Object[]> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return new Object[0];
        }
        return rows.get(0);
    }

    private PartitionFp buildPriceTableFp(String table, Object[] row, boolean withCloseVol) {
        long count = asLong(row, 0);
        LocalDate min = asDate(row, 1);
        LocalDate max = asDate(row, 2);
        String sumClose = asNum(row, 3);
        String sumVol = asNum(row, 4);
        String sumCloseVol = withCloseVol && row.length > 5 ? asNum(row, 5) : "0";
        String maxIngested = withCloseVol && row.length > 6 ? asText(row, 6) : asText(row, 5);
        String fp = count + "|" + nullToEmpty(min) + "|" + nullToEmpty(max)
                + "|" + sumClose + "|" + sumVol + "|" + sumCloseVol + "|" + maxIngested;
        return new PartitionFp(table, count, min, max, fp, sha256(fp));
    }

    private PartitionFp buildAdjustFp(Object[] row) {
        long count = asLong(row, 0);
        LocalDate min = asDate(row, 1);
        LocalDate max = asDate(row, 2);
        String sumFactor = asNum(row, 3);
        String maxIngested = asText(row, 4);
        String fp = count + "|" + nullToEmpty(min) + "|" + nullToEmpty(max)
                + "|" + sumFactor + "|" + maxIngested;
        return new PartitionFp("adjust_factor", count, min, max, fp, sha256(fp));
    }

    private PartitionFp buildMasterFp(Object[] row) {
        long count = asLong(row, 0);
        LocalDate min = asDate(row, 1);
        LocalDate max = asDate(row, 2);
        String maxIngested = asText(row, 3);
        String fp = count + "|" + nullToEmpty(min) + "|" + nullToEmpty(max) + "|" + maxIngested;
        return new PartitionFp("security_master_history", count, min, max, fp, sha256(fp));
    }

    private PartitionFp buildCalendarFp(Object[] row) {
        long count = asLong(row, 0);
        LocalDate min = asDate(row, 1);
        LocalDate max = asDate(row, 2);
        String maxIngested = asText(row, 3);
        String fp = count + "|" + nullToEmpty(min) + "|" + nullToEmpty(max) + "|" + maxIngested;
        return new PartitionFp("trading_calendar", count, min, max, fp, sha256(fp));
    }

    private PartitionFp buildIndexFp(Object[] row) {
        long count = asLong(row, 0);
        LocalDate min = asDate(row, 1);
        LocalDate max = asDate(row, 2);
        String sumClose = asNum(row, 3);
        String sumVol = asNum(row, 4);
        String maxIngested = asText(row, 5);
        String fp = count + "|" + nullToEmpty(min) + "|" + nullToEmpty(max)
                + "|" + sumClose + "|" + sumVol + "|" + maxIngested;
        return new PartitionFp("index_quote", count, min, max, fp, sha256(fp));
    }

    private static long asLong(Object[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) {
            return 0L;
        }
        if (row[i] instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(row[i].toString());
    }

    private static LocalDate asDate(Object[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) {
            return null;
        }
        if (row[i] instanceof LocalDate d) {
            return d;
        }
        if (row[i] instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(row[i].toString());
    }

    private static String asNum(Object[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) {
            return "0";
        }
        return row[i].toString();
    }

    private static String asText(Object[] row, int i) {
        if (row == null || i >= row.length || row[i] == null) {
            return "";
        }
        if (row[i] instanceof LocalDateTime ldt) {
            return ldt.toString();
        }
        return row[i].toString();
    }

    private static String nullToEmpty(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    private String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算快照 hash 失败", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            // V2-MP-030: 禁止序列化失败静默写 {} 空产物；fail-closed
            throw new IllegalStateException("数据快照 payload 序列化失败", e);
        }
    }

    private record PartitionFp(
            String tableName,
            long rowCount,
            LocalDate minTradeDate,
            LocalDate maxTradeDate,
            String fingerprint,
            String contentHash
    ) {}
}
