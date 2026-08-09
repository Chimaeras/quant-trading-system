package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.research.CalibratorService;
import com.quant.research.HistoricalReplayService;
import com.quant.research.HistoricalSignalReplayService;
import com.quant.research.LabelFactoryService;
import com.quant.research.LogisticModelTrainer;
import com.quant.research.ProbabilityCalibrationService;
import com.quant.research.artifact.ModelImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 历史重放与标签工厂研究接口（QP-007 / MP-005+）。
 *
 * <p>暴露股票池健康重放、历史信号重放（轻量 manifest）、T+2/T+3 标签工厂、概率校准 baseline。
 */
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class ResearchController {

    private final HistoricalReplayService historicalReplayService;
    private final HistoricalSignalReplayService historicalSignalReplayService;
    private final LabelFactoryService labelFactoryService;
    private final ProbabilityCalibrationService probabilityCalibrationService;
    private final LogisticModelTrainer logisticModelTrainer;
    private final com.quant.repository.ModelVersionRepository modelVersionRepository;
    private final com.quant.service.scoring.LimitUpEcologyService limitUpEcologyService;
    private final ModelImportService modelImportService;
    private final CalibratorService calibratorService;

    /**
     * QP-007：按交易日区间重放 PIT 历史股票池，生成数据集健康报告。
     * 股票池 = PIT 证券主数据 ∩ 当日有行情；统计每日池规模、PIT/行情缺失。
     * 幂等只读，可断点续跑。兼容路径，正式别名见 {@link #replayPoolHealth}。
     */
    @PostMapping("/replay")
    public ApiResponse<Map<String, Object>> replay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(historicalReplayService.replayPoolHealth(startDate, endDate));
    }

    /** MP-005：股票池健康重放别名，与 {@code /replay} 等价。 */
    @PostMapping("/replay/pool-health")
    public ApiResponse<Map<String, Object>> replayPoolHealth(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(historicalReplayService.replayPoolHealth(startDate, endDate));
    }

    /**
     * MP-005：历史信号重放——逐日复用在线打分（persist=false），落库 FeatureSnapshotManifest。
     * 默认最多 20 个交易日，硬上限 60；策略限 CLASSIC_SELECTOR / OVERSOLD_REBOUND。
     */
    @PostMapping("/replay/signals")
    public ApiResponse<Map<String, Object>> replaySignals(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam String strategyType,
            @RequestParam(required = false) String featureVersion,
            @RequestParam String snapshotId,
            @RequestParam(required = false) Integer maxDays) {
        return ApiResponse.success(historicalSignalReplayService.replaySignals(
                startDate, endDate, strategyType, featureVersion, snapshotId, maxDays));
    }

    /**
     * MP-002：为区间内每个交易日生成 T+2/T+3 标签（基于 PIT 股票池）。
     * 默认 label_version=label_v2_raw_calendar（raw 价 + 市场交易日历）；
     * label_v1* 保留旧 market_data 路径仅供历史只读。dataSnapshotId 必填（V2-MP-002：
     * 新研究必须绑定真实快照，禁止把空 snapshot 自动替换成「最新」）。
     */
    @PostMapping("/labels/build")
    public ApiResponse<Map<String, Object>> buildLabels(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String labelVersion,
            @RequestParam String dataSnapshotId) {
        return ApiResponse.success(labelFactoryService.buildLabels(startDate, endDate, labelVersion, dataSnapshotId));
    }

    /** T+1 次日方向标签（close-to-close，与 T+2/T+3 成交口径标签并行）。 */
    @GetMapping("/t1/ecology")
    public ApiResponse<Map<String, Object>> t1Ecology(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "30") int lookbackDays) {
        return ApiResponse.success(limitUpEcologyService.ecology(tradeDate, lookbackDays));
    }

    /** 涨停次日溢价统计（涨停股在次日的表现，用于候选解释与验证）。 */
    @GetMapping("/t1/premium")
    public ApiResponse<Map<String, Object>> t1Premium(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "50") int topLimitUpCount) {
        return ApiResponse.success(limitUpEcologyService.nextDayPremium(tradeDate, topLimitUpCount));
    }

    /** T+1 次日方向标签（close-to-close，与 T+2/T+3 成交口径标签并行）。默认 label_v2_raw_calendar。 */
    @PostMapping("/labels/build-t1")
    public ApiResponse<Map<String, Object>> buildT1Labels(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "label_v2_raw_calendar") String labelVersion) {
        return ApiResponse.success(labelFactoryService.buildT1Labels(startDate, endDate, labelVersion));
    }

    /** QP-008：标签覆盖率统计（exec_status 分布）。默认 label_v2_raw_calendar。 */
    @GetMapping("/labels/coverage")
    public ApiResponse<Map<String, Object>> labelCoverage(
            @RequestParam(defaultValue = "label_v2_raw_calendar") String labelVersion) {
        return ApiResponse.success(labelFactoryService.buildCoverage(labelVersion));
    }

    /**
     * MP-009：规则分数概率校准（单策略 + fit/eval embargo + PAV isotonic）。
     * V2-MP-002：默认 label_v2_raw_calendar；dataSnapshotId 必填（新研究必须绑定快照）。
     */
    @GetMapping("/calibration")
    public ApiResponse<Map<String, Object>> calibration(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fitStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fitEnd,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate evalStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate evalEnd,
            @RequestParam String strategyType,
            @RequestParam(required = false) String factorVersion,
            @RequestParam String dataSnapshotId,
            @RequestParam(required = false) String experimentId,
            @RequestParam(defaultValue = "label_v2_raw_calendar") String labelVersion,
            @RequestParam(defaultValue = "2") int horizon,
            @RequestParam(required = false) String baseModelVersion) {
        return ApiResponse.success(probabilityCalibrationService.calibrate(
                fitStart, fitEnd, evalStart, evalEnd, labelVersion, horizon,
                strategyType, factorVersion, dataSnapshotId, experimentId, baseModelVersion));
    }

    /** V2-MP-004：校准器注册表（最近 20 条，只读审计；不含内嵌 JSON 本体）。 */
    @GetMapping("/calibrators")
    public ApiResponse<java.util.List<Map<String, Object>>> calibrators() {
        return ApiResponse.success(calibratorService.listLatest(20));
    }

    /** QP-013：模型注册表（最近 20 条，供前端模型列表）。V2-MP-025：只回元数据 lineage，不含大 artifact。 */
    @GetMapping("/models")
    public ApiResponse<java.util.List<Map<String, Object>>> models() {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.quant.entity.ModelVersion mv : modelVersionRepository.findTop20ByOrderByCreatedAtDesc()) {
            out.add(modelImportService.metadata(mv));
        }
        return ApiResponse.success(out);
    }

    /**
     * MP-003：训练 Logistic 单特征 rule_score 基线（强制 strategyType + embargo）。
     * V2-MP-002：默认 label_v2_raw_calendar；dataSnapshotId 必填（新研究必须绑定快照）。
     */
    @PostMapping("/logistic/train")
    public ApiResponse<Map<String, Object>> trainLogistic(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainEnd,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testEnd,
            @RequestParam String strategyType,
            @RequestParam(required = false) String factorVersion,
            @RequestParam String dataSnapshotId,
            @RequestParam(required = false) String experimentId,
            @RequestParam(defaultValue = "label_v2_raw_calendar") String labelVersion,
            @RequestParam(defaultValue = "2") int horizon,
            @RequestParam(defaultValue = "logistic_v1_t2") String modelVersion) {
        return ApiResponse.success(logisticModelTrainer.train(
                trainStart, trainEnd, testStart, testEnd, labelVersion, horizon, modelVersion,
                strategyType, factorVersion, dataSnapshotId, experimentId));
    }
}
