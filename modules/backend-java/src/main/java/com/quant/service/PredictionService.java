package com.quant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.ModelVersion;
import com.quant.entity.PredictionRun;
import com.quant.entity.PredictionSnapshotItem;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.exception.BusinessException;
import com.quant.repository.ModelVersionRepository;
import com.quant.repository.PredictionRunRepository;
import com.quant.repository.PredictionSnapshotItemRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.research.ResearchConvention;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> generate(LocalDate tradeDate, int horizon) {
        if (tradeDate == null) {
            throw new BusinessException("tradeDate 不能为空");
        }
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon)) {
            throw new BusinessException("horizon 仅支持 T+2 / T+3，当前=" + horizon);
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
                DataQualityGateService.PURPOSE_DAILY_PUBLISH, null);
        String gateStatus = String.valueOf(gate.get("gate_status"));

        ChampionLoad championLoad = loadChampionForHorizon(horizon);

        PredictionRun ruleRun = persistTrack(
                tradeDate, horizon, PredictionRun.TRACK_RULE_SCORE,
                PredictionRun.STATUS_PUBLISHED, null,
                null, pick, items, gate, null);

        String abstainReason = null;
        String probStatus = PredictionRun.STATUS_PUBLISHED;
        ProbModel probModel = null;
        if (championLoad.model() == null) {
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
                gate, probModel);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("horizon", horizon);
        out.put("quality_gate", gate);
        out.put("tracks", List.of(toRunSummary(ruleRun), toRunSummary(probRun)));
        out.put("rule_score_run_id", ruleRun.getRunId());
        out.put("probability_run_id", probRun.getRunId());
        out.put("note", "规则轨始终发布；概率轨需 champion+schema/horizon 匹配且 DAILY_PUBLISH=PASS，否则 ABSTAIN");
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
            ProbModel inferModel
    ) {
        int nextVersion = predictionRunRepository.findMaxPublishVersion(tradeDate, horizon, track) + 1;
        String runId = "PRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        PredictionRun run = new PredictionRun();
        run.setRunId(runId);
        run.setTradeDate(tradeDate);
        run.setHorizon(horizon);
        run.setTrack(track);
        run.setPublishVersion(nextVersion);
        run.setStatus(status);
        run.setAbstainReason(abstainReason);
        if (probModel != null) {
            run.setModelVersion(probModel.modelVersion());
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
                    score = inferModel.predictProbability(ruleScore);
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

    private SelectorPick pickSelectorResult(LocalDate tradeDate) {
        List<SelectorResult> dayResults =
                selectorResultRepository.findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(tradeDate);
        Map<String, SelectorResult> latest = new LinkedHashMap<>();
        for (SelectorResult r : dayResults) {
            latest.putIfAbsent(r.getStrategyType(), r);
        }
        SelectorResult preferred = latest.get(DEFAULT_STRATEGY);
        if (preferred == null && !latest.isEmpty()) {
            preferred = latest.values().iterator().next();
        }
        if (preferred == null) {
            // 回退：取该策略全局最新 SUCCESS（可能日期不同，lineage 会标注）
            preferred = selectorResultRepository
                    .findFirstByStrategyTypeOrderByCreatedAtDesc(DEFAULT_STRATEGY)
                    .filter(r -> "SUCCESS".equals(r.getRunStatus()))
                    .orElse(null);
            if (preferred != null && !tradeDate.equals(preferred.getTradeDate())) {
                log.warn("预测日 {} 无选股结果，回退使用 {} 的 {}", tradeDate,
                        preferred.getStrategyType(), preferred.getTradeDate());
            }
        }
        return new SelectorPick(preferred, preferred != null ? preferred.getStrategyType() : DEFAULT_STRATEGY);
    }

    private ChampionLoad loadChampionForHorizon(int horizon) {
        try {
            ModelVersion mv = modelVersionRepository
                    .findFirstByHorizonAndStageOrderByCreatedAtDesc(horizon, "champion")
                    .orElse(null);
            if (mv == null || mv.getArtifact() == null || mv.getArtifact().isBlank()) {
                return new ChampionLoad(null, "NO_CHAMPION_MODEL");
            }
            if (mv.getHorizon() == null || mv.getHorizon() != horizon) {
                return new ChampionLoad(null, "HORIZON_MISMATCH");
            }
            String featureVersion = mv.getFeatureVersion();
            if (featureVersion == null || featureVersion.isBlank()) {
                return new ChampionLoad(null, "FEATURE_SCHEMA_MISSING");
            }
            // T+2/T+3 允许 rule_score 基线；禁止误用 T+1 专用 schema
            if (featureVersion.toLowerCase().contains("t1")
                    && !featureVersion.toLowerCase().contains("t" + horizon)) {
                return new ChampionLoad(null, "FEATURE_SCHEMA_MISMATCH:" + featureVersion);
            }
            Map<String, Object> art = objectMapper.readValue(
                    mv.getArtifact(), new TypeReference<Map<String, Object>>() {});
            List<Number> weightsRaw = castNumberList(art.get("weights"));
            Number biasRaw = art.get("bias") instanceof Number num ? num : null;
            if (weightsRaw == null || weightsRaw.isEmpty()) {
                return new ChampionLoad(null, "ARTIFACT_INVALID");
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
            return new ChampionLoad(
                    new ProbModel(weights, bias, featureMin, featureMax, mv.getModelVersion(), featureVersion),
                    null);
        } catch (Exception e) {
            log.warn("加载 horizon={} champion 失败: {}", horizon, e.getMessage());
            return new ChampionLoad(null, "LOAD_ERROR:" + e.getMessage());
        }
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
            lineage.put("model_version", model.modelVersion());
            lineage.put("feature_version", model.featureVersion());
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
            return "{}";
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
            return Map.of();
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

    private record ProbModel(
            double[] weights, double bias, double featureMin, double featureMax,
            String modelVersion, String featureVersion
    ) {
        double predictProbability(double ruleScore) {
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
    }
}
