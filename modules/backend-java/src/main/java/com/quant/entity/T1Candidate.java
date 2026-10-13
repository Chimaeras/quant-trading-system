package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * T+1 次日候选清单（核心用户工作流）。
 *
 * <p>T 日收盘生成「次日上涨候选 Top10」，T+1 结束后回填次日表现核销。
 * 候选与核销结果以 JSON 落库，支持按 {@code tradeDate} 回溯历史某天的候选与命中率。
 *
 * <p>评分说明：候选排序优先用 T+1 概率（若已训练 horizon=1 模型），否则回退规则分
 * （短期动量 + 量能 + 趋势位置，0–50 的 rule_score，非概率）。概率输出要求匹配且绑定的 champion 校准器。
 */
@Data
@Entity
@Table(name = "t1_candidate", indexes = {
        @Index(name = "idx_t1_candidate_date", columnList = "tradeDate")
})
public class T1Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 信号日（T 日），候选在该日收盘后生成。 */
    @Column(nullable = false, unique = true)
    private LocalDate tradeDate;

    /** 候选条数（默认 10）。 */
    @Column(nullable = false)
    private Integer topN = 10;

    /** 候选清单 JSON：[{stockCode, exchangeType, stockName, score, is_probability, reasons, limit_up, lianban_days, ...}]。 */
    @Column(columnDefinition = "LONGTEXT")
    private String candidatesJson;

    /** 是否已完成次日核销。 */
    @Column(nullable = false)
    private Boolean settled = false;

    /** 次日核销 JSON：{next_trade_date, summary:{hit_count, hit_rate, avg_next_ret, avg_gap, high_open_count}, results:[...]}。 */
    @Column(columnDefinition = "LONGTEXT")
    private String settledJson;

    /** 生成时的市场情绪快照 JSON（涨停家数/连板高度/晋级率）。 */
    @Column(columnDefinition = "TEXT")
    private String ecologyJson;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
