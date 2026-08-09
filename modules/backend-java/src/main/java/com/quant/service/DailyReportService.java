package com.quant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.DailyReport;
import com.quant.entity.LabelSnapshot;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.PredictionRun;
import com.quant.entity.PredictionSnapshotItem;
import com.quant.entity.SelectorResult;
import com.quant.entity.StockInfo;
import com.quant.exception.BusinessException;
import com.quant.repository.DailyReportRepository;
import com.quant.repository.LabelSnapshotRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.PredictionRunRepository;
import com.quant.repository.PredictionSnapshotItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.research.ResearchConvention;
import com.quant.research.artifact.ArtifactManifestValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 选股日报（V2-MP-012）：prediction snapshot 的不可变投影。
 *
 * <p>口径（MP-012，替代旧的「selector_result 聚合 + 旁挂 lineage」）：
 * <ul>
 *   <li>请求显式选 {@code (tradeDate, horizon, track, publishVersion)}；正文候选只读
 *       {@link PredictionSnapshotItem}（不可变），与 prediction item hash 一致。</li>
 *   <li>无 PUBLISHED run 时日报 abstain，绝不回退昨日 selector，也不动态拼装无关 lineage。</li>
 *   <li>每个 (tradeDate,horizon,track,publishVersion) 恰好一条 {@link DailyReport} 投影头，
 *       report_version = publish_version；重跑只新增更高版本，不覆盖历史。</li>
 *   <li>到期后追加 realization（label_v2 成交状态/净收益，退回 raw close-to-close，缺 raw 一律
 *       UNAVAILABLE，无 qfq 回退），追加不修改原候选。</li>
 *   <li>行业/名称：名称用 prediction item 的 PIT stock_name；行业用当前 stock_info 并标注
 *       provenance=CURRENT_STOCK_INFO（历史 PIT 行业依赖 MP-017，未落地前如实标注）。</li>
 * </ul>
 *
 * <p>多日信号趋势（{@link #buildTrendReport}）是独立的 selector 视角视图，不参与本投影。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportService {

    /** 单行业占比超过该阈值时提示集中度风险 */
    private static final double INDUSTRY_CONCENTRATION_WARN_PCT = 0.40;
    /** realization 优先 join 的标签版本（预测 run 未落 labelVersion，取研究默认）。 */
    private static final String REALIZATION_LABEL_VERSION = "label_v2";

    private final PredictionRunRepository predictionRunRepository;
    private final PredictionSnapshotItemRepository predictionSnapshotItemRepository;
    private final DailyReportRepository dailyReportRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final StockInfoRepository stockInfoRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final LabelSnapshotRepository labelSnapshotRepository;
    private final TradingCalendarService tradingCalendarService;
    private final ObjectMapper objectMapper;

    /**
     * 构建日报投影。tradeDate 省略时解析为全部预测快照的最新信号日；
     * publishVersion 省略时取该轨道最新发布版本（含 ABSTAIN，如实呈现）。
     * 首次生成会惰性落投影头（幂等），故为读-写事务。
     */
    @Transactional
    public Map<String, Object> buildDailyReport(
            LocalDate tradeDate, int horizon, String track, Integer publishVersion) {
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)) {
            throw new BusinessException("horizon 仅支持 2 或 3");
        }
        if (!PredictionRun.TRACK_RULE_SCORE.equals(track) && !PredictionRun.TRACK_PROBABILITY.equals(track)) {
            throw new BusinessException("track 仅支持 RULE_SCORE / PROBABILITY");
        }
        LocalDate date = tradeDate != null ? tradeDate : predictionRunRepository.findMaxTradeDate().orElse(null);
        if (date == null) {
            return abstainState(null, horizon, track, publishVersion, "NOT_PUBLISHED",
                    "尚无任何预测快照，日报 abstain（绝不回退昨日或动态 selector 内容）");
        }
        return buildForDate(date, horizon, track, publishVersion);
    }

    /** 某日某轨道全部可发布的版本（前端版本选择器）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> listVersions(LocalDate tradeDate, int horizon, String track) {
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)) {
            throw new BusinessException("horizon 仅支持 2 或 3");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate != null ? tradeDate.toString() : null);
        out.put("horizon", horizon);
        out.put("track", track);
        List<Map<String, Object>> versions = new ArrayList<>();
        if (tradeDate != null) {
            List<PredictionRun> runs =
                    predictionRunRepository.findByTradeDateAndHorizonAndTrackOrderByPublishVersionDesc(tradeDate, horizon, track);
            for (PredictionRun r : runs) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("publish_version", r.getPublishVersion());
                v.put("status", r.getStatus());
                v.put("abstain_reason", r.getAbstainReason());
                v.put("item_count", r.getItemCount());
                v.put("prediction_run_id", r.getRunId());
                v.put("model_version", r.getModelVersion());
                versions.add(v);
            }
        }
        out.put("versions", versions);
        return out;
    }

    /**
     * 到期后追加 realization（幂等：已落库直接返回，不覆盖）。
     *
     * <p>到期判定：exit = tradeDate 后第 horizon 个交易日，且 exit ≤ 今日；未到期返回
     * NOT_READY 不落库。逐标的：优先 join {@code label_v2} 成交状态/净收益（FILLED 为权威
     * 可成交收益，拒单状态如实呈现）；label 缺失退回 raw close-to-close（RAW_CLOSE_ONLY）；
     * raw 缺失一律 UNAVAILABLE（fail-closed，无 qfq 回退）。
     */
    @Transactional
    public Map<String, Object> realize(LocalDate tradeDate, int horizon, String track, Integer publishVersion) {
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)) {
            throw new BusinessException("horizon 仅支持 2 或 3");
        }
        LocalDate date = tradeDate != null ? tradeDate : predictionRunRepository.findMaxTradeDate().orElse(null);
        if (date == null) {
            throw new BusinessException("尚无任何预测快照，无法追加 realization");
        }
        PredictionRun run = resolveRun(date, horizon, track, publishVersion);
        if (run == null || !PredictionRun.STATUS_PUBLISHED.equals(run.getStatus())) {
            throw new BusinessException("该日 T+" + horizon + " " + track + " 无 PUBLISHED 预测快照，"
                    + "realization 不可用（fail-closed）");
        }
        List<PredictionSnapshotItem> items = predictionSnapshotItemRepository.findByRunIdOrderByRankNoAsc(run.getRunId());
        DailyReport report = ensureReport(run, items);

        if (report.getRealizationJson() != null && !report.getRealizationJson().isBlank()) {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("reused", true);
            stored.put("message", "realization 已落库（幂等，不重复计算）");
            Map<String, Object> real = parseJsonMap(report.getRealizationJson());
            stored.put("stored_status", real.get("status"));
            stored.putAll(real);
            return stored;
        }

        Optional<LocalDate> maturityOpt = tradingCalendarService.tradingDayAfterN(date, horizon);
        LocalDate today = LocalDate.now();
        if (maturityOpt.isEmpty() || maturityOpt.get().isAfter(today)) {
            Map<String, Object> notReady = new LinkedHashMap<>();
            notReady.put("status", "NOT_READY");
            notReady.put("signal_date", date.toString());
            notReady.put("maturity_date", maturityOpt.map(LocalDate::toString).orElse(null));
            notReady.put("horizon", horizon);
            notReady.put("note", "持有期（T+" + horizon + "）未到期，暂不落库；到期后再次调用");
            return notReady;
        }
        LocalDate maturity = maturityOpt.get();

        List<Map<String, Object>> perStock = new ArrayList<>();
        int filled = 0;
        int rawOnly = 0;
        int unavailable = 0;
        for (PredictionSnapshotItem item : items) {
            Map<String, Object> r = realizeStock(item, date, maturity, horizon);
            String st = str(r.get("realization_status"));
            if ("FILLED".equals(st)) {
                filled++;
            } else if ("RAW_CLOSE_ONLY".equals(st)) {
                rawOnly++;
            } else {
                unavailable++;
            }
            perStock.add(r);
        }

        Map<String, Object> realization = new LinkedHashMap<>();
        realization.put("status", unavailable == 0 ? "COMPLETE" : "PARTIAL");
        realization.put("signal_date", date.toString());
        realization.put("maturity_date", maturity.toString());
        realization.put("horizon", horizon);
        realization.put("label_version", REALIZATION_LABEL_VERSION);
        realization.put("label_join_note",
                "realization 优先 join label_v2 成交状态与净收益（FILLED=权威可成交收益，拒单状态如实呈现）；"
                        + "label 缺失时退回 raw close-to-close（RAW_CLOSE_ONLY）；缺 raw 一律 UNAVAILABLE，无 qfq 回退");
        realization.put("per_stock", perStock);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("filled", filled);
        counts.put("raw_close_only", rawOnly);
        counts.put("unavailable", unavailable);
        counts.put("total", items.size());
        realization.put("counts", counts);
        report.setRealizationJson(writeJson(realization));
        dailyReportRepository.save(report);
        return realization;
    }

    // ── 投影核心 ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildForDate(LocalDate date, int horizon, String track, Integer publishVersion) {
        PredictionRun run = resolveRun(date, horizon, track, publishVersion);
        if (run == null) {
            return abstainState(date, horizon, track, publishVersion, "NOT_PUBLISHED",
                    "该日 T+" + horizon + " " + track + " 无预测快照（指定版本不存在），日报 abstain，绝不回退昨日");
        }
        if (PredictionRun.STATUS_ABSTAIN.equals(run.getStatus())) {
            return abstainState(date, horizon, track, run.getPublishVersion(), "ABSTAIN",
                    run.getAbstainReason() != null ? run.getAbstainReason() : "ABSTAIN");
        }
        List<PredictionSnapshotItem> items =
                predictionSnapshotItemRepository.findByRunIdOrderByRankNoAsc(run.getRunId());
        DailyReport report = ensureReport(run, items);

        Map<String, Object> out = baseHeader(run);
        out.put("status", PredictionRun.STATUS_PUBLISHED);
        out.put("report_id", report.getReportId());
        out.put("report_version", report.getReportVersion());
        out.put("content_hash", report.getContentHash());
        out.put("generated_at", report.getGeneratedAt() != null ? report.getGeneratedAt().toString() : null);
        out.put("lineage", buildLineage(run));
        out.put("items", items.stream().map(this::toItemMap).toList());
        out.put("industry_concentration", buildIndustryConcentration(items));
        out.put("realization", report.getRealizationJson() != null && !report.getRealizationJson().isBlank()
                ? parseJsonMap(report.getRealizationJson()) : null);
        out.put("note",
                "日报 = prediction snapshot(" + run.getRunId() + ") 的不可变投影；候选集合 content_hash 校验一致。"
                        + "重跑 selector/prediction 只会新增更高 report_version，绝不覆盖历史报告。");
        return out;
    }

    private PredictionRun resolveRun(LocalDate date, int horizon, String track, Integer publishVersion) {
        List<PredictionRun> runs =
                predictionRunRepository.findByTradeDateAndHorizonAndTrackOrderByPublishVersionDesc(date, horizon, track);
        if (publishVersion != null) {
            return runs.stream().filter(r -> publishVersion.equals(r.getPublishVersion())).findFirst().orElse(null);
        }
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * 确保投影头存在（幂等）；已存在时复算 content_hash 校验，不一致即 fail-closed。
     * 并发首建时靠唯一约束兜底：DataIntegrityViolationException → 重读既有行（不覆盖）。
     */
    private DailyReport ensureReport(PredictionRun run, List<PredictionSnapshotItem> items) {
        Optional<DailyReport> existing = dailyReportRepository.findByTradeDateAndHorizonAndTrackAndPublishVersion(
                run.getTradeDate(), run.getHorizon(), run.getTrack(), run.getPublishVersion());
        if (existing.isPresent()) {
            return verifyHash(existing.get(), items, run.getRunId());
        }
        DailyReport report = new DailyReport();
        report.setReportId(buildReportId(run));
        report.setTradeDate(run.getTradeDate());
        report.setHorizon(run.getHorizon());
        report.setTrack(run.getTrack());
        report.setPublishVersion(run.getPublishVersion());
        report.setReportVersion(run.getPublishVersion());
        report.setPredictionRunId(run.getRunId());
        report.setSourceResultId(run.getSelectorResultId());
        report.setFactorVersion(run.getFactorVersion());
        report.setModelVersion(run.getModelVersion());
        Map<String, Object> lineage = parseJsonMap(run.getLineageJson());
        report.setOutputKind(str(lineage.get("output_kind")));
        report.setSchemaHash(str(lineage.get("schema_hash")));
        report.setDeployKey(str(lineage.get("deploy_key")));
        report.setCalibratorVersion(str(lineage.get("calibrator_version")));
        report.setCalibratorHash(str(lineage.get("calibrator_hash")));
        report.setSnapshotId(str(lineage.get("checked_snapshot_id")));
        report.setContentHash(computeContentHash(items));
        report.setItemCount(items.size());
        try {
            dailyReportRepository.save(report);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发首建：唯一约束 uk_daily_report_snapshot 兜底，重读既有行
            return verifyHash(dailyReportRepository
                    .findByTradeDateAndHorizonAndTrackAndPublishVersion(
                            run.getTradeDate(), run.getHorizon(), run.getTrack(), run.getPublishVersion())
                    .orElseThrow(() -> new IllegalStateException("日报投影头并发首建冲突后重读缺失", e)),
                    items, run.getRunId());
        }
        return report;
    }

    private DailyReport verifyHash(DailyReport report, List<PredictionSnapshotItem> items, String runId) {
        String recomputed = computeContentHash(items);
        if (!recomputed.equalsIgnoreCase(report.getContentHash())) {
            // P0 fail-closed：prediction_snapshot_item 不可变，hash 不一致说明正文被篡改，禁止静默降级
            throw new IllegalStateException("日报 content_hash 与 prediction snapshot 不一致，禁止静默降级："
                    + "run_id=" + runId);
        }
        return report;
    }

    private Map<String, Object> abstainState(LocalDate date, int horizon, String track, Integer publishVersion,
                                             String status, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("trade_date", date != null ? date.toString() : null);
        out.put("horizon", horizon);
        out.put("track", track);
        out.put("publish_version", publishVersion);
        out.put("abstain_reason", reason);
        out.put("items", List.of());
        out.put("note", status + "：无 PUBLISHED 预测快照时日报 abstain，绝不回退昨日 selector 或动态拼装 lineage");
        return out;
    }

    private Map<String, Object> baseHeader(PredictionRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", run.getTradeDate().toString());
        out.put("horizon", run.getHorizon());
        out.put("track", run.getTrack());
        out.put("publish_version", run.getPublishVersion());
        out.put("prediction_run_id", run.getRunId());
        out.put("source_result_id", run.getSelectorResultId());
        out.put("factor_version", run.getFactorVersion());
        out.put("item_count", run.getItemCount());
        out.put("abstain_reason", run.getAbstainReason());
        return out;
    }

    /** 从 prediction run 的 lineage_json 提取固定模型/校准器/快照字段（不含候选正文）。 */
    private Map<String, Object> buildLineage(PredictionRun run) {
        Map<String, Object> lineage = parseJsonMap(run.getLineageJson());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("convention_version", lineage.get("convention_version"));
        out.put("gate_status", lineage.get("gate_status"));
        out.put("gate_decision_id", lineage.get("gate_decision_id"));
        out.put("snapshot_id", lineage.get("checked_snapshot_id"));
        out.put("factor_version", run.getFactorVersion());
        out.put("strategy_type", run.getStrategyType());
        out.put("model_version", run.getModelVersion());
        out.put("output_kind", lineage.get("output_kind"));
        out.put("schema_hash", lineage.get("schema_hash"));
        out.put("deploy_key", lineage.get("deploy_key"));
        out.put("calibrator_version", lineage.get("calibrator_version"));
        out.put("calibrator_hash", lineage.get("calibrator_hash"));
        out.put("inference_chain", lineage.get("inference_chain"));
        out.put("source_result_id", run.getSelectorResultId());
        out.put("source_trade_date", lineage.get("selector_trade_date"));
        return out;
    }

    private Map<String, Object> toItemMap(PredictionSnapshotItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank_no", item.getRankNo());
        m.put("stock_code", item.getStockCode());
        m.put("exchange_type", item.getExchangeType());
        m.put("stock_name", item.getStockName());
        m.put("score", item.getScore());
        m.put("score_type", item.getScoreType());
        m.put("reasons", parseJsonMap(item.getReasonsJson()));
        return m;
    }

    /**
     * 候选行业分布。行业来自当前 stock_info（MP-017 未落地前非 PIT，如实标注 provenance）；
     * industry 缺失归入「未分类」。
     */
    private Map<String, Object> buildIndustryConcentration(List<PredictionSnapshotItem> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provenance", "CURRENT_STOCK_INFO");
        out.put("pit_note", "行业来自当前 stock_info；历史同日 PIT 行业依赖 MP-017 位时主数据，未落地前以当前行业标注");
        if (items == null || items.isEmpty()) {
            out.put("total", 0);
            out.put("industries", List.of());
            out.put("max_concentration_pct", 0.0);
            out.put("warning", null);
            out.put("threshold_pct", INDUSTRY_CONCENTRATION_WARN_PCT * 100);
            return out;
        }
        List<String> keys = items.stream()
                .map(i -> i.getStockCode() + "|" + i.getExchangeType())
                .toList();
        Map<String, String> industryByKey = new LinkedHashMap<>();
        for (StockInfo info : stockInfoRepository.findByStockKeyIn(keys)) {
            String ind = info.getIndustry();
            industryByKey.put(info.getStockCode() + "|" + info.getExchangeType(),
                    ind != null && !ind.isBlank() ? ind : "未分类");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PredictionSnapshotItem item : items) {
            String key = item.getStockCode() + "|" + item.getExchangeType();
            String industry = industryByKey.getOrDefault(key, "未分类");
            counts.merge(industry, 1, Integer::sum);
        }
        int total = items.size();
        List<Map<String, Object>> industries = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("industry", e.getKey());
                    m.put("count", e.getValue());
                    m.put("pct", Math.round(e.getValue() * 10000.0 / total) / 100.0);
                    industries.add(m);
                });
        double maxPct = industries.isEmpty() ? 0.0 : ((Number) industries.get(0).get("pct")).doubleValue() / 100.0;
        String warning = null;
        if (maxPct >= INDUSTRY_CONCENTRATION_WARN_PCT) {
            warning = "候选集中在「" + industries.get(0).get("industry")
                    + "」(" + industries.get(0).get("pct") + "%)，注意单一行业集中风险";
        }
        out.put("total", total);
        out.put("industries", industries);
        out.put("max_concentration_pct", Math.round(maxPct * 10000.0) / 100.0);
        out.put("warning", warning);
        out.put("threshold_pct", INDUSTRY_CONCENTRATION_WARN_PCT * 100);
        return out;
    }

    private Map<String, Object> realizeStock(PredictionSnapshotItem item, LocalDate signalDate, LocalDate maturity,
                                             int horizon) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rank_no", item.getRankNo());
        r.put("stock_code", item.getStockCode());
        r.put("exchange_type", item.getExchangeType());
        r.put("stock_name", item.getStockName());

        MarketPriceRaw entry = marketPriceRawRepository
                .findByStockCodeAndExchangeTypeAndTradeDate(item.getStockCode(), item.getExchangeType(), signalDate)
                .orElse(null);
        MarketPriceRaw exit = marketPriceRawRepository
                .findByStockCodeAndExchangeTypeAndTradeDate(item.getStockCode(), item.getExchangeType(), maturity)
                .orElse(null);
        BigDecimal entryClose = entry != null ? entry.getClosePrice() : null;
        BigDecimal exitClose = exit != null ? exit.getClosePrice() : null;
        r.put("entry_close", entryClose != null ? entryClose : null);
        r.put("exit_close", exitClose != null ? exitClose : null);
        Double rawRet = null;
        if (entryClose != null && exitClose != null && entryClose.signum() != 0) {
            rawRet = round8(exitClose.doubleValue() / entryClose.doubleValue() - 1.0);
        }
        r.put("raw_forward_return", rawRet);

        LabelSnapshot label = labelSnapshotRepository
                .findByLabelVersionAndSignalDateAndStockCodeAndExchangeTypeAndHorizon(
                        REALIZATION_LABEL_VERSION, signalDate, item.getStockCode(), item.getExchangeType(), horizon)
                .orElse(null);
        String status;
        Double netReturn = null;
        if (label != null) {
            r.put("label_exec_status", label.getExecStatus());
            if ("FILLED".equals(label.getExecStatus()) && label.getNetReturn() != null) {
                netReturn = round8(label.getNetReturn().doubleValue());
                status = "FILLED";
            } else {
                status = label.getExecStatus();
            }
        } else {
            status = rawRet != null ? "RAW_CLOSE_ONLY" : "UNAVAILABLE";
        }
        r.put("label_net_return", netReturn);
        r.put("realization_status", status);
        return r;
    }

    // ── 确定性 hash / 工具 ────────────────────────────────────────────────────

    /** 候选集合确定性 SHA-256：按 rank/code/exchange 排序后逐项拼接（含 reasonsJson 原文）。 */
    private String computeContentHash(List<PredictionSnapshotItem> items) {
        List<String> lines = items.stream()
                .sorted(Comparator
                        .comparingInt((PredictionSnapshotItem i) -> i.getRankNo() != null ? i.getRankNo() : Integer.MAX_VALUE)
                        .thenComparing(PredictionSnapshotItem::getStockCode)
                        .thenComparing(PredictionSnapshotItem::getExchangeType))
                .map(i -> (i.getRankNo() != null ? i.getRankNo() : 0) + "|" + i.getStockCode() + "|" + i.getExchangeType()
                        + "|" + nz(i.getStockName()) + "|" + i.getScore() + "|" + nz(i.getScoreType())
                        + "|" + nz(i.getReasonsJson()))
                .toList();
        return ArtifactManifestValidator.sha256Hex(String.join("\n", lines));
    }

    private String buildReportId(PredictionRun run) {
        String raw = run.getTradeDate() + "|" + run.getHorizon() + "|" + run.getTrack() + "|" + run.getPublishVersion();
        return "RPT" + ArtifactManifestValidator.sha256Hex(raw).substring(0, 16).toUpperCase();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // V2-MP-030: 禁止序列化失败静默写空产物；fail-closed
            throw new IllegalStateException("日报 realization 序列化失败，禁止静默写入", e);
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // V2-MP-030: 禁止损坏 JSON 静默降级为空 map 并标成功；fail-closed
            throw new IllegalStateException("日报 JSON 损坏，禁止静默降级", e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static double round8(double v) {
        return Math.round(v * 100000000.0) / 100000000.0;
    }

    // ── 多日信号趋势（独立的 selector 视角视图，不参与 projection）──────────

    /**
     * 多日信号趋势：给定日期范围，输出每日各策略候选数量与机会变化。
     *
     * <p>口径：
     * <ul>
     *   <li>数据来源：已持久化的 selector_result（SUCCESS），无未来函数。</li>
     *   <li>同策略同日取最新一条；按 trade_date 升序。</li>
     *   <li>仅统计 returned_items 与 has_opportunity，不展开候选明细。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildTrendReport(LocalDate startDate, LocalDate endDate) {
        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(startDate, endDate);

        // 同策略同日取最新
        Map<String, SelectorResult> latestPerKey = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerKey.putIfAbsent(r.getStrategyType() + "|" + r.getTradeDate(), r);
        }

        // 按 trade_date 分组
        Map<LocalDate, List<SelectorResult>> byDate = new LinkedHashMap<>();
        for (SelectorResult r : latestPerKey.values()) {
            byDate.computeIfAbsent(r.getTradeDate(), k -> new ArrayList<>()).add(r);
        }
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        dates.sort(LocalDate::compareTo);

        // 收集所有策略名
        List<String> strategies = latestPerKey.values().stream()
                .map(SelectorResult::getStrategyType)
                .distinct()
                .sorted()
                .toList();

        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        for (LocalDate d : dates) {
            List<SelectorResult> dayResults = byDate.get(d);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("trade_date", d.toString());
            int totalCandidates = 0;
            int strategiesWithOpportunity = 0;
            Map<String, Integer> perStrategy = new LinkedHashMap<>();
            for (String s : strategies) {
                perStrategy.put(s, 0);
            }
            for (SelectorResult r : dayResults) {
                int returned = r.getSuccessCandidates() != null ? r.getSuccessCandidates() : 0;
                perStrategy.put(r.getStrategyType(), returned);
                totalCandidates += returned;
                if (returned > 0) {
                    strategiesWithOpportunity++;
                }
            }
            day.put("strategies_with_opportunity", strategiesWithOpportunity);
            day.put("total_candidates", totalCandidates);
            day.put("per_strategy", perStrategy);
            dailyTrend.add(day);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("strategies", strategies);
        out.put("signal_days", dates.size());
        out.put("daily_trend", dailyTrend);
        out.put("note", "多日信号趋势：基于已持久化选股结果，无未来函数；returned_items=0 视为该策略当日无机会");
        return out;
    }
}
