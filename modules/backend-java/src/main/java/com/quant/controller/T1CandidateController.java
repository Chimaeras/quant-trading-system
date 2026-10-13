package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.scheduler.T1CandidateScheduler;
import com.quant.service.T1CandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * T+1 次日候选核心闭环：T 日收盘生成候选 Top10 → 次日核销 → 历史回溯。
 */
@RestController
@RequestMapping("/api/research/t1")
@RequiredArgsConstructor
public class T1CandidateController {

    private final T1CandidateService t1CandidateService;
    private final T1CandidateScheduler t1CandidateScheduler;

    /** T 日收盘生成「次日上涨候选 TopN」并落库。 */
    @PostMapping("/candidates")
    public ApiResponse<Map<String, Object>> generate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.success(t1CandidateService.generateCandidates(tradeDate, topN));
    }

    /** T+1 结束后核销：回填候选次日表现并聚合命中率。 */
    @PostMapping("/settle")
    public ApiResponse<Map<String, Object>> settle(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        return ApiResponse.success(t1CandidateService.settleCandidates(tradeDate));
    }

    /** 按日回溯候选（含核销结果）。 */
    @GetMapping("/candidates/{tradeDate}")
    public ApiResponse<Map<String, Object>> get(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
        return ApiResponse.success(t1CandidateService.getCandidates(tradeDate));
    }

    /** 最近生成的候选列表（摘要）。 */
    @GetMapping("/recent")
    public ApiResponse<List<Map<String, Object>>> recent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(t1CandidateService.recentCandidates(limit));
    }

    /** T+1 次日方向统计（未扣费用/滑点，非可执行策略净收益）。 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(t1CandidateService.strategyStats(startDate, endDate));
    }

    /**
     * 定时生成任务的最近一次执行状态（只读）。
     *
     * <p>用途：定时任务若静默失败，外部只能看到「候选清单没更新」而不知原因。
     * 该端点暴露 开关/表达式/上次结果/距上次运行秒数，便于观测与告警。
     * 不触发任何生成或写库。
     */
    @GetMapping("/schedule/status")
    public ApiResponse<Map<String, Object>> scheduleStatus() {
        return ApiResponse.success(t1CandidateScheduler.status());
    }

    /**
     * 手动触发一次「与定时任务完全相同」的生成流程（走同一条漏跑自愈 + 幂等逻辑）。
     *
     * <p>存在意义：定时任务默认关闭，且 cron 只在固定时刻触发，运维/验证时无法立刻看到
     * 效果。该端点复用同一个 {@code runOnce}，保证「手动跑的」与「定时跑的」行为一致，
     * 不会出现两套逻辑。
     */
    @PostMapping("/schedule/trigger")
    public ApiResponse<Map<String, Object>> scheduleTrigger() {
        return ApiResponse.success(t1CandidateScheduler.runOnce("MANUAL"));
    }
}
