package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.DataSnapshot;
import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.DataSnapshotRepository;
import com.quant.repository.IndexQuoteRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.TradingCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据快照服务（QP-006）。
 *
 * <p>记录某一 cutoff 时点的数据底座状态（各表行数、source 版本、质量状态、content hash），
 * 供历史重放与实验复现：同一 snapshotId 重跑应得到完全一致的数据和特征。
 *
 * <p>当前为「元数据快照」（行数/source/质量状态），非完整数据内容 hash；
 * 后续 QP-007 历史重放服务将引用 snapshotId 锁定数据版本。
 *
 * <p>snapshotId = cutoff(yyyyMMdd) + 当日序号(001/002/...)；同一 cutoff 可多次创建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSnapshotService {

    private final DataSnapshotRepository dataSnapshotRepository;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final AdjustFactorRepository adjustFactorRepository;
    private final SecurityMasterHistoryRepository securityMasterHistoryRepository;
    private final TradingCalendarDayRepository tradingCalendarDayRepository;
    private final IndexQuoteRepository indexQuoteRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建数据快照（收集各表行数 + source 分布 + 质量状态，计算 SHA-256 hash）。
     *
     * @param cutoffTime 数据截止时点（null 时取 market_data 最新交易日）。
     * @return 创建的 DataSnapshot。
     */
    @Transactional
    public DataSnapshot createSnapshot(LocalDate cutoffTime) {
        // 1. 确定 cutoff
        if (cutoffTime == null) {
            cutoffTime = marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());
        }

        // 2. 收集各表行数
        Map<String, Object> rowCounts = new LinkedHashMap<>();
        rowCounts.put("market_data", marketDataRepository.count());
        rowCounts.put("market_price_raw", marketPriceRawRepository.countAll());
        rowCounts.put("adjust_factor", adjustFactorRepository.countAll());
        rowCounts.put("security_master_history", securityMasterHistoryRepository.count());
        rowCounts.put("trading_calendar", tradingCalendarDayRepository.count());
        rowCounts.put("index_quote", indexQuoteRepository.count());

        // 3. 收集 source 分布
        Map<String, Object> sourceVersions = new LinkedHashMap<>();
        Map<String, Object> rawSource = new LinkedHashMap<>();
        rawSource.put("eastmoney_fqt0", marketPriceRawRepository.countAll()); // raw 当前仅 eastmoney 一源
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

        // 4. 质量状态摘要
        Map<String, Object> qualityStatus = new LinkedHashMap<>();
        qualityStatus.put("cutoff", cutoffTime.toString());
        qualityStatus.put("market_data_min", marketDataRepository.findMinTradeDate().map(LocalDate::toString).orElse(""));
        qualityStatus.put("market_data_max", marketDataRepository.findMaxTradeDate().map(LocalDate::toString).orElse(""));
        qualityStatus.put("raw_min", marketPriceRawRepository.findMinTradeDate().map(LocalDate::toString).orElse(""));
        qualityStatus.put("raw_max", marketPriceRawRepository.findMaxTradeDate().map(LocalDate::toString).orElse(""));

        // 5. 计算 content hash（row_counts + source_versions 拼接 SHA-256）
        String hash = computeHash(rowCounts, sourceVersions);

        // 6. 生成 snapshotId
        String snapshotId = generateSnapshotId(cutoffTime);

        DataSnapshot snap = new DataSnapshot();
        snap.setSnapshotId(snapshotId);
        snap.setCutoffTime(cutoffTime);
        snap.setRowCounts(toJson(rowCounts));
        snap.setSourceVersions(toJson(sourceVersions));
        snap.setQualityStatus(toJson(qualityStatus));
        snap.setHash(hash);
        snap.setCreatedBy("manual");
        dataSnapshotRepository.save(snap);

        log.info("数据快照创建: snapshotId={}, cutoff={}, hash={}", snapshotId, cutoffTime, hash);
        return snap;
    }

    /** 最近 N 条快照（降序）。 */
    @Transactional(readOnly = true)
    public List<DataSnapshot> listLatest(int limit) {
        return dataSnapshotRepository.findLatest(Math.max(1, limit));
    }

    /** 全量快照计数（供覆盖率审计）。 */
    @Transactional(readOnly = true)
    public long count() {
        return dataSnapshotRepository.count();
    }

    private String generateSnapshotId(LocalDate cutoff) {
        String prefix = cutoff.format(DateTimeFormatter.BASIC_ISO_DATE);
        // 当日已有快照数 + 1 作为序号
        long existing = dataSnapshotRepository.findBySnapshotIdStartingWith(prefix).size();
        return prefix + "-" + String.format("%03d", existing + 1);
    }

    private String computeHash(Map<String, Object> rowCounts, Map<String, Object> sourceVersions) {
        try {
            String content = toJson(rowCounts) + "|" + toJson(sourceVersions);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
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
            return "{}";
        }
    }
}
