package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.entity.ExperimentRun;
import com.quant.exception.ResourceNotFoundException;
import com.quant.research.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实验注册表 API（MP-016）。
 */
@RestController
@RequestMapping("/api/research/experiments")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentService experimentService;

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(
            @RequestParam String name,
            @RequestParam(required = false) String config,
            @RequestParam(required = false) String gitCommit,
            @RequestParam(required = false) Long seed,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(required = false) String labelVersion,
            @RequestParam(required = false) String modelVersions,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate trainEnd,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate testEnd,
            @RequestParam(required = false) String metrics,
            @RequestParam(required = false, defaultValue = "registered") String status) {
        ExperimentRun run = experimentService.register(
                name, config, gitCommit, seed, snapshotId, labelVersion, modelVersions,
                trainStart, trainEnd, testStart, testEnd, metrics, status);
        return ApiResponse.success(experimentService.toSummary(run));
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExperimentRun run : experimentService.listLatest(limit)) {
            out.add(experimentService.toSummary(run));
        }
        return ApiResponse.success(out);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable("id") String id) {
        ExperimentRun run = experimentService.getById(id);
        if (run == null) {
            // 资源不存在用 404（与其它详情接口一致）；BusinessException 会被映射成 400
            throw new ResourceNotFoundException("实验不存在: " + id);
        }
        Map<String, Object> summary = experimentService.toSummary(run);
        summary.put("gates", experimentService.evaluatePromoteGates(id));
        return ApiResponse.success(summary);
    }

    @GetMapping("/{id}/gates")
    public ApiResponse<Map<String, Object>> gates(
            @PathVariable("id") String id,
            @RequestParam(required = false) String modelVersion) {
        return ApiResponse.success(experimentService.evaluatePromoteGates(id, modelVersion));
    }

    /**
     * 人工决策：action=CHALLENGER|REJECT。不自动晋升 champion（晋级用 /promote）。
     */
    @PostMapping("/{id}/decision")
    public ApiResponse<Map<String, Object>> decision(
            @PathVariable("id") String id,
            @RequestParam String action,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) String actor) {
        return ApiResponse.success(experimentService.recordDecision(id, action, note, actor));
    }

    /**
     * V2-MP-018：人工 champion 晋级（原子事务，双人可审计）。
     * 硬门禁 FAIL 或并发冲突 → 409；单人工确认可配置 quant.experiment.promotion.confirm-required=false。
     */
    @PostMapping("/{id}/promote")
    public ApiResponse<Map<String, Object>> promote(
            @PathVariable("id") String id,
            @RequestParam String modelVersion,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String confirmActor,
            @RequestParam(required = false) String note) {
        return ApiResponse.success(experimentService.promoteChampion(id, modelVersion, actor, confirmActor, note));
    }

    /** V2-MP-018：回滚到已验证版本（原子事务，双人可审计）。 */
    @PostMapping("/{id}/rollback")
    public ApiResponse<Map<String, Object>> rollback(
            @PathVariable("id") String id,
            @RequestParam(required = false) String modelVersion,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String confirmActor,
            @RequestParam(required = false) String note) {
        return ApiResponse.success(experimentService.rollback(id, modelVersion, actor, confirmActor, note));
    }
}
