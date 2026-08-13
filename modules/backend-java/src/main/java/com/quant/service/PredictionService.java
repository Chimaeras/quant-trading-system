package com.quant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.Calibrator;
import com.quant.entity.ModelVersion;
import com.quant.entity.PredictionRun;
import com.quant.entity.PredictionSnapshotItem;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.exception.BusinessException;
import com.quant.exception.ResourceNotFoundException;
import com.quant.repository.ModelVersionRepository;
import com.quant.repository.PredictionRunRepository;
import com.quant.repository.PredictionSnapshotItemRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.research.CalibratorService;
import com.quant.research.FeatureSchemaRegistry;
import com.quant.research.LogisticModelTrainer;
import com.quant.research.ResearchConvention;
import com.quant.research.artifact.ArtifactManifestValidator;
import com.quant.research.artifact.ModelDeploymentContract;
import com.quant.research.artifact.ModelImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每日 T+2/T+3 双轨预测快照（MP-011）。
 *
 * <p>规则轨始终发布；概率轨仅在 champion + schema/horizon 匹配且 DAILY_PUBLISH 质量门禁 PASS 时发布，否则 ABSTAIN。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final String DEFAULT_STRATEGY = "CLASSIC_SELECTOR";
    private static final int DEFAULT_TOP_N = 20;

    private final PredictionRunRepository predictionRunRepository;
    private final PredictionSnapshotItemRepository predictionSnapshotItemRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final DataQualityGateService dataQualityGateService;
    private final CalibratorService calibratorService;
    private final FeatureSchemaRegistry featureSchemaRegistry;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> generate(LocalDate tradeDate, int horizon) {
        return generate(tradeDate, horizon, null);
    }

    @Transactional
    public Map<String, Object> generate(LocalDate tradeDate, int horizon, String requestId) {
        if (tradeDate == null) {
            throw new BusinessException("tradeDate 不能为空");
        }
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)) {
            throw new BusinessException("horizon 仅支持 T+2 / T+3，当前=" + horizon);
        }
        long startMs = System.currentTimeMillis();
        if (requestId != null && !requestId.isBlank()) {
            // V2-MP-030: 同 requestId 重复发布返回既有结果（幂等重放），不重复生成 publish_version
            PredictionRun existing = predictionRunRepository
                    .findFirstByRequestIdAndTradeDateAndHorizon(requestId.trim(), tradeDate, horizon)
                    .orElse(null);
            if (existing != null) {
                log.info("预测发布幂等重放 trade_date={} horizon={} request_id={} elapsed_ms={}",
                        tradeDate, horizon, requestId, System.currentTimeMillis() - startMs);
                return buildIdempotentResponse(tradeDate, horizon,
                        predictionRunRepository.findByRequestId(requestId.trim()));
            }
        }

        SelectorPick pick = pickSelectorResult(tradeDate);
        List<SelectorResultItem> items = pick.result() != null
                ? selectorResultItemRepository.findByResultIdInOrderByRankNo(List.of(pick.result().getResultId()))
                : List.of();
        items = items.stream()
                .sorted(Comparator.comparingInt(i -> i.getRankNo() != null ? i.getRankNo() : Integer.MAX_VALUE))
                .limit(DEFAULT_TOP_N)
                .toList();

        Map<String, Object> gate = dataQualityGateService.evaluate(
                DataQualityGateService.GateRequest.of(
                        DataQualityGateService.PURPOSE_DAILY_PUBLISH, null));
        String gateStatus = String.valueOf(gate.get("gate_status"));

        ChampionLoad championLoad = loadProbDeploy(horizon, pick);

        // P0（MP-001）：目标日无同日选股结果时，规则轨也必须 abstain，绝不跨交易日/跨策略回退。
        boolean noSameDaySelector = pick.result() == null;
        String noSelectorReason = "NO_SAME_DAY_SELECTOR_RESULT";

        PredictionRun ruleRun = persistTrack(
                tradeDate, horizon, PredictionRun.TRACK_RULE_SCORE,
                noSameDaySelector ? PredictionRun.STATUS_ABSTAIN : PredictionRun.STATUS_PUBLISHED,
                noSameDaySelector ? noSelectorReason : null,
                null, pick, items, gate, null, requestId);

        String abstainReason = null;
        String probStatus = PredictionRun.STATUS_PUBLISHED;
        ProbModel probModel = null;
        if (noSameDaySelector) {
            probStatus = PredictionRun.STATUS_ABSTAIN;
            abstainReason = noSelectorReason;
        } else if (championLoad.model() == null) {
            probStatus = PredictionRun.STATUS_ABSTAIN;
            abstainReason = championLoad.reason() != null ? championLoad.reason() : "NO_CHAMPION_MODEL";
        } else if (!DataQualityGateService.STATUS_PASS.equals(gateStatus)) {
            probStatus = PredictionRun.STATUS_ABSTAIN;
            abstainReason = "QUALITY_GATE_" + gateStatus;
        } else {
            probModel = championLoad.model();
        }

        PredictionRun probRun = persistTrack(
                tradeDate, horizon, PredictionRun.TRACK_PROBABILITY,
                probStatus, abstainReason,
                probModel, pick,
                PredictionRun.STATUS_ABSTAIN.equals(probStatus) ? List.of() : items,
                gate, probModel, requestId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("horizon", horizon);
        out.put("quality_gate", gate);
        out.put("tracks", List.of(toRunSummary(ruleRun), toRunSummary(probRun)));
        out.put("rule_score_run_id", ruleRun.getRunId());
        out.put("probability_run_id", probRun.getRunId());
        out.put("source_result_id", pick.result() != null ? pick.result().getResultId() : null);
        out.put("source_trade_date", pick.result() != null ? pick.result().getTradeDate().toString() : null);
        out.put("note", "规则轨仅在目标日存在同日 selector result 时发布（MP-001：禁止跨交易日/跨策略回退）；"
                + "概率轨需 champion+schema/horizon 匹配且 DAILY_PUBLISH=PASS，否则 ABSTAIN");
        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("预测发布完成 trade_date={} horizon={} elapsed_ms={} gate_status={} rule_run={} prob_run={}",
                tradeDate, horizon, elapsedMs, gateStatus, ruleRun.getRunId(), probRun.getRunId());
        return out;
    }

    /**
     * 幂等重放响应（V2-MP-030）：同 requestId 已发布时，用既有 run 重建响应，不触发任何写入。
     * 注：quality_gate 仅展示用途；重放以「返回既有结果」为最高目标，其解析失败不阻断重放。
     */
    private Map<String, Object> buildIdempotentResponse(
            LocalDate tradeDate, int horizon, List<PredictionRun> runs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("horizon", horizon);
        out.put("idempotent", true);
        out.put("message", "同 requestId 已存在发布结果，直接返回（幂等，未重复生成）");
        if (!runs.isEmpty()) {
            PredictionRun first = runs.get(0);
            out.put("rule_score_run_id", runs.stream()
                    .filter(r -> PredictionRun.TRACK_RULE_SCORE.equals(r.getTrack()))
                    .map(PredictionRun::getRunId).findFirst().orElse(null));
            out.put("probability_run_id", runs.stream()
                    .filter(r -> PredictionRun.TRACK_PROBABILITY.equals(r.getTrack()))
                    .map(PredictionRun::getRunId).findFirst().orElse(null));
            if (first.getQualityGateJson() != null && !first.getQualityGateJson().isBlank()) {
                try {
                    out.put("quality_gate", parseJson(first.getQualityGateJson()));
                } catch (Exception e) {
                    out.put("quality_gate", Map.of("gate_status", "UNKNOWN"));
                }
            }
        }
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (PredictionRun run : runs) {
            Map<String, Object> t = toRunSummary(run);
            List<PredictionSnapshotItem> items =
                    predictionSnapshotItemRepository.findByRunIdInOrderByRankNoAsc(List.of(run.getRunId()));
            t.put("items", items.stream().map(this::toItemMap).toList());
            tracks.add(t);
        }
        out.put("tracks", tracks);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDaily(LocalDate tradeDate, int horizon) {
        if (tradeDate == null) {
            throw new BusinessException("date 不能为空");
        }
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)
                && horizon != ResearchConvention.HORIZON_T1) {
            throw new BusinessException("horizon 仅支持 1/2/3");
        }
        List<PredictionRun> runs = predictionRunRepository.findLatestByTradeDateAndHorizon(tradeDate, horizon);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("horizon", horizon);
        if (runs.isEmpty()) {
            out.put("published", false);
            out.put("tracks", List.of());
            out.put("message", "NOT_PUBLISHED");
            out.put("note", "该日尚未生成预测快照，请 POST /api/predictions/generate");
            return out;
        }
        List<String> runIds = runs.stream().map(PredictionRun::getRunId).toList();
        List<PredictionSnapshotItem> allItems =
                predictionSnapshotItemRepository.findByRunIdInOrderByRankNoAsc(runIds);
        Map<String, List<PredictionSnapshotItem>> byRun = new LinkedHashMap<>();
        for (PredictionSnapshotItem item : allItems) {
            byRun.computeIfAbsent(item.getRunId(), k -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        for (PredictionRun run : runs) {
            Map<String, Object> t = toRunSummary(run);
            t.put("items", byRun.getOrDefault(run.getRunId(), List.of()).stream()
                    .map(this::toItemMap).toList());
            tracks.add(t);
        }
        out.put("published", true);
        out.put("tracks", tracks);
        return out;
    }

    /** V2-MP-020：最近 prediction run 列表（组合回测闭环信号源选择器）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRuns(int limit) {
        int l = Math.min(50, Math.max(1, limit));
        Pageable pageable = PageRequest.of(0, l, Sort.by(Sort.Direction.DESC, "createdAt"));
        return predictionRunRepository.findAll(pageable).getContent().stream()
                .map(this::toRunSummary)
                .toList();
    }

    /**
     * V2-MP-028：按 runId 拉取单个 prediction run 详情（含 items）。
     * 生成发布后前端按返回的 runId 拉详情，避免「生成成功但 items 为空」被误读为成功空结果。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getRunDetail(String runId) {
        PredictionRun run = predictionRunRepository.findByRunId(runId)
                .orElseThrow(() -> new ResourceNotFoundException("预测 run 不存在: " + runId));
        Map<String, Object> m = toRunSummary(run);
        List<PredictionSnapshotItem> items =
                predictionSnapshotItemRepository.findByRunIdInOrderByRankNoAsc(List.of(runId));
        m.put("items", items.stream().map(this::toItemMap).toList());
        return m;
    }

    private PredictionRun persistTrack(
            LocalDate tradeDate,
            int horizon,
            String track,
            String status,
            String abstainReason,
            ProbModel probModel,
            SelectorPick pick,
            List<SelectorResultItem> items,
            Map<String, Object> gate,
            ProbModel inferModel,
            String requestId
    ) {
        int nextVersion = predictionRunRepository.findMaxPublishVersion(tradeDate, horizon, track) + 1;
        String runId = "PRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        PredictionRun run = new PredictionRun();
        run.setRunId(runId);
        run.setRequestId(requestId != null && !requestId.isBlank() ? requestId.trim() : null);
        run.setTradeDate(tradeDate);
        run.setHorizon(horizon);
        run.setTrack(track);
        run.setPublishVersion(nextVersion);
        run.setStatus(status);
        run.setAbstainReason(abstainReason);
        if (probModel != null) {
            run.setModelVersion(probModel.modelVersion);
        }
        if (pick.result() != null) {
            run.setFactorVersion(pick.result().getFactorVersion());
            run.setStrategyType(pick.result().getStrategyType());
            run.setSelectorResultId(pick.result().getResultId());
        } else {
            run.setStrategyType(pick.strategyType());
        }
        run.setQualityGateJson(writeJson(gate));
        run.setLineageJson(writeJson(buildLineage(pick, gate, probModel, status, abstainReason)));

        List<PredictionSnapshotItem> snapshotItems = new ArrayList<>();
        if (PredictionRun.STATUS_PUBLISHED.equals(status) && !items.isEmpty()) {
            int rank = 1;
            List<ScoredCandidate> scored = new ArrayList<>();
            for (SelectorResultItem item : items) {
                double ruleScore = item.getFinalScore() != null ? item.getFinalScore() : 0;
                double score = ruleScore;
                String scoreType = "RULE_SCORE";
                if (PredictionRun.TRACK_PROBABILITY.equals(track) && inferModel != null) {
                    score = inferModel.predictProbability(calibratorService, ruleScore);
                    scoreType = "CALIBRATED_PROBABILITY";
                }
                scored.add(new ScoredCandidate(item, score, scoreType, ruleScore));
            }
            if (PredictionRun.TRACK_PROBABILITY.equals(track)) {
                scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());
            }
            for (ScoredCandidate c : scored) {
                PredictionSnapshotItem row = new PredictionSnapshotItem();
                row.setRunId(runId);
                row.setTradeDate(tradeDate);
                row.setHorizon(horizon);
                row.setTrack(track);
                row.setPublishVersion(nextVersion);
                row.setStockCode(c.item().getStockCode());
                row.setExchangeType(c.item().getExchangeType());
                row.setStockName(c.item().getStockName());
                row.setRankNo(rank++);
                row.setScore(round4(c.score()));
                row.setScoreType(c.scoreType());
                Map<String, Object> reasons = new LinkedHashMap<>();
                reasons.put("rule_score", round4(c.ruleScore()));
                reasons.put("selector_rank", c.item().getRankNo());
                if (PredictionRun.TRACK_PROBABILITY.equals(track) && inferModel != null) {
                    reasons.put("base_probability", round4(inferModel.baseProbability(c.ruleScore())));
                    reasons.put("base_model_version", inferModel.modelVersion);
                    reasons.put("output_kind", inferModel.outputKind);
                    reasons.put("schema_hash", inferModel.schemaHash);
                    reasons.put("deploy_key", inferModel.deployKey);
                    reasons.put("calibrator_version", inferModel.calibratorVersion);
                    reasons.put("calibrator_hash", inferModel.calibratorHash);
                }
                row.setReasonsJson(writeJson(reasons));
                snapshotItems.add(row);
            }
        }
        run.setItemCount(snapshotItems.size());
        predictionRunRepository.save(run);
        if (!snapshotItems.isEmpty()) {
            predictionSnapshotItemRepository.saveAll(snapshotItems);
        }
        return run;
    }

    /**
     * 选择目标日的选股结果（MP-001 P0 修复）。
     *
     * <p>只接受「同日 + 指定策略」的成功结果；缺失时返回 {@code result=null}，
     * 由调用方决定 abstain。绝不允许跨交易日回退到全局最新结果，也不允许任意选取
     * 同日的其他策略——否则历史预测会读取未来选股结果，当日预测会把陈旧候选伪装成今日数据。
     */
    private SelectorPick pickSelectorResult(LocalDate tradeDate) {
        List<SelectorResult> dayResults =
                selectorResultRepository.findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(tradeDate);
        SelectorResult preferred = null;
        for (SelectorResult r : dayResults) {
            if (DEFAULT_STRATEGY.equals(r.getStrategyType())) {
                if (preferred == null) {
                    preferred = r;
                }
            }
        }
        if (preferred == null) {
            log.warn("预测日 {} 无 {} 策略的成功选股结果，预测 abstain（MP-001：禁止跨日/跨策略回退）",
                    tradeDate, DEFAULT_STRATEGY);
        }
        return new SelectorPick(preferred, DEFAULT_STRATEGY);
    }

    /**
     * V2-MP-004：按原子部署契约加载概率 champion。
     *
     * <p>顺序：按精确 deploy key（algorithm:rule_score_v1:schema_hash）+ stage=champion +
     * 策略 + 持有期查找 base model → 契约完整性 + 上下文字段匹配 → 加载 champion 校准器
     * （base_model_version 原子绑定）→ 配对一致性（label/snapshot/code 版本）。任一不符/缺失
     * 即返回 abstain 原因，绝不自动挑任意 champion。
     */
    private ChampionLoad loadProbDeploy(int horizon, SelectorPick pick) {
        String strategyType = pick.strategyType();
        String featureSetVersion = LogisticModelTrainer.FEATURE_VERSION;
        String expectedSchemaHash;
        try {
            expectedSchemaHash = featureSchemaRegistry.resolveSchemaHash(strategyType, featureSetVersion);
        } catch (Exception e) {
            return new ChampionLoad(null, "FEATURE_SCHEMA_UNKNOWN:" + featureSetVersion);
        }
        String deployKey = ModelImportService.buildDeployKey(
                LogisticModelTrainer.ALGORITHM, featureSetVersion, expectedSchemaHash);
        ModelVersion mv;
        try {
            mv = modelVersionRepository
                    .findFirstByDeployKeyAndStageAndStrategyTypeAndHorizonOrderByCreatedAtDesc(
                            deployKey, "champion", strategyType, horizon)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("概率 champion 查找失败 horizon={} strategy={}: {}", horizon, strategyType, e.getMessage());
            return new ChampionLoad(null, "CONTRACT_LOOKUP_ERROR:" + e.getMessage());
        }
        if (mv == null) {
            return new ChampionLoad(null, "NO_CHAMPION_CONTRACT");
        }
        if (!ModelDeploymentContract.isContractComplete(mv)) {
            return new ChampionLoad(null, "CONTRACT_INCOMPLETE");
        }
        List<String> ctxMis = ModelDeploymentContract.contextMismatches(
                mv, strategyType, horizon, featureSetVersion, expectedSchemaHash,
                LogisticModelTrainer.OUTPUT_KIND);
        if (!ctxMis.isEmpty()) {
            return new ChampionLoad(null, "CONTRACT_MISMATCH:" + String.join(",", ctxMis));
        }

        BaseArtifact base;
        try {
            base = parseBaseArtifact(mv);
        } catch (Exception e) {
            return new ChampionLoad(null, "ARTIFACT_INVALID:" + e.getMessage());
        }

        // 原子配对：champion 校准器必须显式绑定该 base model 版本，且策略/horizon/label/snapshot 一致。
        Calibrator cal = calibratorService.loadChampion(strategyType, horizon, mv.getModelVersion()).orElse(null);
        if (cal == null) {
            return new ChampionLoad(null, "NO_CHAMPION_CALIBRATOR");
        }
        if (cal.getCalibratorJson() == null || cal.getCalibratorJson().isBlank()) {
            return new ChampionLoad(null, "CALIBRATOR_EMPTY");
        }
        if (cal.getCalibratorHash() == null
                || !cal.getCalibratorHash().equalsIgnoreCase(
                        ArtifactManifestValidator.sha256Hex(cal.getCalibratorJson()))) {
            return new ChampionLoad(null, "CALIBRATOR_HASH_MISMATCH");
        }
        List<String> pairMis = ModelDeploymentContract.calibratorPairMismatches(mv, cal);
        if (!pairMis.isEmpty()) {
            return new ChampionLoad(null, "CALIBRATOR_CONTRACT_MISMATCH:" + String.join(",", pairMis));
        }

        ProbModel model = new ProbModel(
                mv.getModelVersion(), mv.getFeatureSetVersion(), mv.getSchemaHash(),
                mv.getOutputKind(), mv.getDeployKey(),
                base.weights(), base.bias(), base.featureMin(), base.featureMax(),
                cal, cal.getCalibratorVersion(), cal.getCalibratorHash());
        return new ChampionLoad(model, null);
    }

    private record BaseArtifact(double[] weights, double bias, double featureMin, double featureMax) {
    }

    private BaseArtifact parseBaseArtifact(ModelVersion mv) throws Exception {
        Map<String, Object> art = objectMapper.readValue(
                mv.getArtifact(), new TypeReference<Map<String, Object>>() {});
        List<Number> weightsRaw = castNumberList(art.get("weights"));
        Number biasRaw = art.get("bias") instanceof Number num ? num : null;
        if (weightsRaw == null || weightsRaw.isEmpty()) {
            throw new BusinessException("artifact weights 缺失或为空");
        }
        double[] weights = weightsRaw.stream().mapToDouble(Number::doubleValue).toArray();
        double bias = biasRaw != null ? biasRaw.doubleValue() : 0;
        double featureMin = 0;
        double featureMax = 1;
        Object pre = art.get("preprocessor");
        if (pre instanceof Map<?, ?> preMap) {
            List<Number> minList = castNumberList(preMap.get("min"));
            List<Number> maxList = castNumberList(preMap.get("max"));
            if (minList != null && !minList.isEmpty()) {
                featureMin = minList.get(0).doubleValue();
            }
            if (maxList != null && !maxList.isEmpty()) {
                featureMax = maxList.get(0).doubleValue();
            }
        }
        return new BaseArtifact(weights, bias, featureMin, featureMax);
    }

    private Map<String, Object> buildLineage(
            SelectorPick pick,
            Map<String, Object> gate,
            ProbModel model,
            String status,
            String abstainReason
    ) {
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("convention_version", ResearchConvention.CONVENTION_VERSION);
        lineage.put("cost_version", ResearchConvention.CONVENTION_VERSION);
        lineage.put("gate_status", gate.get("gate_status"));
        lineage.put("gate_decision_id", gate.get("decision_id"));
        lineage.put("checked_snapshot_id", gate.get("checked_snapshot_id"));
        lineage.put("threshold_version", gate.get("threshold_version"));
        if (pick.result() != null) {
            lineage.put("selector_result_id", pick.result().getResultId());
            lineage.put("selector_trade_date", pick.result().getTradeDate() != null
                    ? pick.result().getTradeDate().toString() : null);
            lineage.put("factor_version", pick.result().getFactorVersion());
            lineage.put("strategy_type", pick.result().getStrategyType());
        }
        if (model != null) {
            lineage.put("model_version", model.modelVersion);
            lineage.put("feature_set_version", model.featureSetVersion);
            lineage.put("schema_hash", model.schemaHash);
            lineage.put("output_kind", model.outputKind);
            lineage.put("deploy_key", model.deployKey);
            lineage.put("calibrator_version", model.calibratorVersion);
            lineage.put("calibrator_hash", model.calibratorHash);
            lineage.put("inference_chain", "feature->base_probability/score->persisted_calibrator->calibrated_probability");
        }
        lineage.put("status", status);
        if (abstainReason != null) {
            lineage.put("abstain_reason", abstainReason);
        }
        return lineage;
    }

    private Map<String, Object> toRunSummary(PredictionRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prediction_run_id", run.getRunId());
        m.put("trade_date", run.getTradeDate().toString());
        m.put("horizon", run.getHorizon());
        m.put("track", run.getTrack());
        m.put("publish_version", run.getPublishVersion());
        m.put("status", run.getStatus());
        m.put("abstain_reason", run.getAbstainReason());
        m.put("model_version", run.getModelVersion());
        m.put("factor_version", run.getFactorVersion());
        m.put("strategy_type", run.getStrategyType());
        m.put("selector_result_id", run.getSelectorResultId());
        m.put("item_count", run.getItemCount());
        m.put("quality_gate", parseJson(run.getQualityGateJson()));
        m.put("lineage", parseJson(run.getLineageJson()));
        m.put("created_at", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toItemMap(PredictionSnapshotItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stock_code", item.getStockCode());
        m.put("exchange_type", item.getExchangeType());
        m.put("stock_name", item.getStockName());
        m.put("rank_no", item.getRankNo());
        m.put("score", item.getScore());
        m.put("score_type", item.getScoreType());
        m.put("track", item.getTrack());
        m.put("reasons", parseJson(item.getReasonsJson()));
        return m;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // V2-MP-030: 禁止序列化失败静默写 {} 空产物；fail-closed
            throw new IllegalStateException("预测 payload 序列化失败，终止发布", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // V2-MP-030: 禁止损坏 JSON 静默降级为空 map 并标成功；fail-closed
            throw new IllegalStateException("预测原因 JSON 损坏，禁止静默降级", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Number> castNumberList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Number>) list;
        }
        return null;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record SelectorPick(SelectorResult result, String strategyType) {}

    private record ChampionLoad(ProbModel model, String reason) {}

    private record ScoredCandidate(SelectorResultItem item, double score, String scoreType, double ruleScore) {}

    /**
     * 概率部署模型（V2-MP-004）：base model（logistic）+ 持久化校准器。
     * {@code predictProbability} 为 特征→base probability/score→校准器→calibrated probability；
     * {@code baseProbability} 供审计记录 base 输出。
     */
    private static final class ProbModel {
        final String modelVersion;
        final String featureSetVersion;
        final String schemaHash;
        final String outputKind;
        final String deployKey;
        final double[] weights;
        final double bias;
        final double featureMin;
        final double featureMax;
        final Calibrator calibrator;
        final String calibratorVersion;
        final String calibratorHash;

        ProbModel(String modelVersion, String featureSetVersion, String schemaHash,
                  String outputKind, String deployKey,
                  double[] weights, double bias, double featureMin, double featureMax,
                  Calibrator calibrator, String calibratorVersion, String calibratorHash) {
            this.modelVersion = modelVersion;
            this.featureSetVersion = featureSetVersion;
            this.schemaHash = schemaHash;
            this.outputKind = outputKind;
            this.deployKey = deployKey;
            this.weights = weights;
            this.bias = bias;
            this.featureMin = featureMin;
            this.featureMax = featureMax;
            this.calibrator = calibrator;
            this.calibratorVersion = calibratorVersion;
            this.calibratorHash = calibratorHash;
        }

        double baseProbability(double ruleScore) {
            double range = featureMax - featureMin;
            double norm = range > 1e-12 ? (ruleScore - featureMin) / range : 0.5;
            double z = bias;
            if (weights.length > 0) {
                z += weights[0] * norm;
            }
            if (z >= 0) {
                double e = Math.exp(-z);
                return 1.0 / (1.0 + e);
            }
            double e = Math.exp(z);
            return e / (1.0 + e);
        }

        double predictProbability(CalibratorService cs, double ruleScore) {
            return cs.apply(calibrator, ruleScore);
        }
    }
}
