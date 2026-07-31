package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.entity.ExperimentRun;
import com.quant.research.ExperimentService;
import com.quant.research.HistoricalReplayService;
import com.quant.research.LabelFactoryService;
import com.quant.research.LogisticModelTrainer;
import com.quant.research.ProbabilityCalibrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 历史重放与标签工厂研究接口（QP-007+）。
 *
 * <p>当前暴露股票池健康重放（QP-007）、T+2/T+3 标签工厂（QP-008）、概率校准 baseline（QP-012）。
 */
@RestController
@RequestMapping("/api/research")
@RequiredArgsConstructor
public class ResearchController {

    private final HistoricalReplayService historicalReplayService;
    private final LabelFactoryService labelFactoryService;
    private final ProbabilityCalibrationService probabilityCalibrationService;
    private final LogisticModelTrainer logisticModelTrainer;
    private final ExperimentService experimentService;

    /**
     * QP-007：按交易日区间重放 PIT 历史股票池，生成数据集健康报告。
     * 股票池 = PIT 证券主数据 ∩ 当日有行情；统计每日池规模、PIT/行情缺失。
     * 幂等只读，可断点续跑。
     */
    @PostMapping("/replay")
    public ApiResponse<Map<String, Object>> replay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(historicalReplayService.replayPoolHealth(startDate, endDate));
    }

    /**
     * QP-008：为区间内每个交易日生成 T+2/T+3 标签（基于 PIT 股票池）。
     * 保留所有拒单样本不净化；幂等跳过已存在。默认 label_version=label_v1。
     */
    @PostMapping("/labels/build")
    public ApiResponse<Map<String, Object>> buildLabels(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "label_v1") String labelVersion) {
        return ApiResponse.success(labelFactoryService.buildLabels(startDate, endDate, labelVersion));
    }

    /** QP-008：标签覆盖率统计（exec_status 分布）。 */
    @GetMapping("/labels/coverage")
    public ApiResponse<Map<String, Object>> labelCoverage(
            @RequestParam(defaultValue = "label_v1") String labelVersion) {
        return ApiResponse.success(labelFactoryService.buildCoverage(labelVersion));
    }

    /**
     * QP-012：规则分数概率校准 baseline。
     * 基于 selector_result_item(rule score) + label_snapshot(y_label) 计算校准指标
     * （Brier/log loss/ECE/可靠性图/sigmoid+isotonic 映射），验证 rule score 与上涨概率的校准关系。
     */
    @GetMapping("/calibration")
    public ApiResponse<Map<String, Object>> calibration(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "label_v1") String labelVersion,
            @RequestParam(defaultValue = "2") int horizon) {
        return ApiResponse.success(probabilityCalibrationService.calibrate(startDate, endDate, labelVersion, horizon));
    }

    /**
     * QP-013：训练 Logistic T+2/T+3 模型（带 L2 正则，版本化训练和解释）。
     * 时间顺序 train/test split + purged embargo；注册到 model_registry。
     */
    @PostMapping("/logistic/train")
    public ApiResponse<Map<String, Object>> trainLogistic(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainEnd,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testEnd,
            @RequestParam(defaultValue = "label_v1") String labelVersion,
            @RequestParam(defaultValue = "2") int horizon,
            @RequestParam(defaultValue = "logistic_v1_t2") String modelVersion) {
        return ApiResponse.success(logisticModelTrainer.train(
                trainStart, trainEnd, testStart, testEnd, labelVersion, horizon, modelVersion));
    }

    /**
     * QP-015：注册一次实验运行（config/git_commit/seed/dataset_ids/windows/metrics/status）。
     * 供完整追溯与复现。
     */
    @PostMapping("/experiments/register")
    public ApiResponse<Map<String, Object>> registerExperiment(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String config,
            @RequestParam(required = false) String gitCommit,
            @RequestParam(required = false) Long seed,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String labelVersion,
            @RequestParam(required = false) String modelVersions,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainEnd,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testEnd,
            @RequestParam(required = false) String metrics,
            @RequestParam(defaultValue = "completed") String status) {
        ExperimentRun run = experimentService.register(name, config, gitCommit, seed,
                snapshotId, labelVersion, modelVersions, trainStart, trainEnd,
                testStart, testEnd, metrics, status);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("experiment_id", run.getExperimentId());
        out.put("name", run.getName());
        out.put("status", run.getStatus());
        out.put("message", "实验已注册：" + run.getExperimentId());
        return ApiResponse.success(out);
    }

    /** QP-015：最近 N 条实验。 */
    @GetMapping("/experiments/list")
    public ApiResponse<Map<String, Object>> listExperiments(
            @RequestParam(defaultValue = "10") int limit) {
        List<ExperimentRun> runs = experimentService.listLatest(limit);
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (ExperimentRun r : runs) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("experiment_id", r.getExperimentId());
            m.put("name", r.getName());
            m.put("git_commit", r.getGitCommit());
            m.put("seed", r.getSeed());
            m.put("snapshot_id", r.getSnapshotId());
            m.put("label_version", r.getLabelVersion());
            m.put("model_versions", r.getModelVersions());
            m.put("train_start", r.getTrainStart() != null ? r.getTrainStart().toString() : "");
            m.put("train_end", r.getTrainEnd() != null ? r.getTrainEnd().toString() : "");
            m.put("test_start", r.getTestStart() != null ? r.getTestStart().toString() : "");
            m.put("test_end", r.getTestEnd() != null ? r.getTestEnd().toString() : "");
            m.put("status", r.getStatus());
            m.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().toString() : "");
            items.add(m);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", runs.size());
        out.put("items", items);
        return ApiResponse.success(out);
    }
}
