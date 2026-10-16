package com.quant.service.scoring.factor;

import com.quant.entity.FactorCrossSectionScore;
import com.quant.repository.FactorCrossSectionScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把统一引擎的**全截面**结果写入 {@code factor_cross_section_score}。
 *
 * <p><b>为什么需要它</b>：{@link LegacyResultRowWriter} 写的是 {@code selector_result_item}，
 * 而 {@link UnifiedScoreReadService#projectStrategy} 在投影后按 {@code topN} 切片，
 * 全截面在切片处被丢弃。Rank IC 是横截面统计量，需要当日**全部**候选，
 * 切片后算出的"IC"只反映头部内部排序（实测该处 T+1 名次 IC 仅 0.017~0.028，
 * 而 IC 标准差 0.22——即纯噪声）。
 *
 * <p><b>写全截面 ≠ 写 Top-N 的放大</b>：本写入器落 {@code eligible=false} 的行
 * （分数为 null）。这看似无用，实则必要：若只落合格者，
 * "合格"本身就是一道筛选，会让 IC 样本被隐式截断，
 * 而研究侧无法从表外观测到这道筛选的存在。
 *
 * <p><b>事务</b>：本组件**不**自带 {@code @Transactional}，写入发生在
 * {@link UnifiedScoreReadService#runAllStrategies} 的外层事务内。
 * 理由：全截面的写入量约 2.7 万行/日，若独立事务则与外层落库不原子，
 * 失败时会出现"item 表已写、截面表半写"的不一致。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FactorCrossSectionWriter {

    private final FactorCrossSectionScoreRepository repository;

    /**
     * 替换某 (交易日, 策略) 的全截面。
     *
     * <p>先删后写而非 upsert：重跑时候选集会变化（新上市、退市、停牌都会改变成员），
     * 残留旧行会让 IC 样本混入已不在当日候选中的股票。
     *
     * @param tradeDate  信号日
     * @param definition 策略定义（取 factorVersion / legacyStrategyType）
     * @param result     引擎的全截面结果（<b>不得</b>传入已切片的列表）
     * @return 实际写入行数
     */    public int replace(
            LocalDate tradeDate,
            StrategyDefinition definition,
            UnifiedFactorScoringEngine.Result result) {

        int deleted = repository.deleteCrossSection(tradeDate, definition.code());
        List<UnifiedFactorScoringEngine.StockScore> ordered = dedupe(orderForPersist(result.scores()));
        if (ordered.isEmpty()) {
            log.warn("全截面为空，跳过写入 date={} strategy={}", tradeDate, definition.code());
            return 0;
        }

        List<FactorCrossSectionScore> rows = new ArrayList<>(ordered.size());
        int rank = 1;
        for (UnifiedFactorScoringEngine.StockScore s : ordered) {
            FactorCrossSectionScore row = new FactorCrossSectionScore();
            row.setTradeDate(tradeDate);
            row.setFactorVersion(definition.factorVersion());
            row.setStrategyCode(definition.code());
            row.setLegacyStrategyType(definition.legacyStrategyType());
            row.setStockCode(s.stockCode());
            row.setExchangeType(s.exchangeType());
            row.setStockName(s.stockName());
            row.setRawScore(s.factorScore());
            row.setFinalScore(s.finalScore());
            row.setCrossSectionRank(rank++);
            row.setAvailableWeightRatio(s.availableWeightRatio());
            row.setEligible(s.eligible());
            row.setRejectReason(truncate(s.rejectReason(), 128));
            row.setFactorPercentiles(percentilesOf(s));
            rows.add(row);
        }
        repository.saveAll(rows);
        log.info("全截面落库 date={} strategy={} 行数={} 删除旧行={}",
                tradeDate, definition.code(), rows.size(), deleted);
        return rows.size();
    }

    /**
     * 排序口径：合格者按分数降序在前，不合格者按原始分降序续后。
     *
     * <p>这样 {@code crossSectionRank} 在"合格集内"与
     * {@link UnifiedScoreReadService#projectStrategy} 的名次完全一致
     * （两者都按 {@code finalScore} 降序、并列按代码升序），
     * 同时不合格者也有稳定位置，便于排查为何被闸门拒绝。
     */
    private static List<UnifiedFactorScoringEngine.StockScore> orderForPersist(
            List<UnifiedFactorScoringEngine.StockScore> scores) {
        List<UnifiedFactorScoringEngine.StockScore> eligible = new ArrayList<>();
        List<UnifiedFactorScoringEngine.StockScore> rejected = new ArrayList<>();
        for (UnifiedFactorScoringEngine.StockScore s : scores) {
            if (s.eligible() && s.finalScore() != null) {
                eligible.add(s);
            } else {
                rejected.add(s);
            }
        }
        eligible.sort(byFinalScoreDesc());
        rejected.sort(Comparator
                .comparingDouble(UnifiedFactorScoringEngine.StockScore::factorScore).reversed()
                .thenComparing(UnifiedFactorScoringEngine.StockScore::stockCode));
        eligible.addAll(rejected);
        return eligible;
    }

    /**
     * 抽取逐因子 L2 百分位，键为 {@code FactorId.name()}。
     *
     * <p><b>只收可用的因子</b>：{@code percentile == null} 表示该因子在这只股票上不可用
     * （数据缺失），此时**不写入键**而非写 null——这样研究侧能区分
     * "因子不可用"（键缺失）与"因子可用且百分位为 0"（键存在、值为 0.0），
     * 后者是合法的极低分，混同会污染 IC 样本。
     *
     * <p>返回 {@code null}（而非空 Map）当且仅当没有任何可用因子，
     * 与 V33 声明的"NULL = 未记录逐因子明细"保持区分。
     */
    private static Map<String, Double> percentilesOf(UnifiedFactorScoringEngine.StockScore s) {
        List<UnifiedFactorScoringEngine.Contribution> contributions = s.contributions();
        if (contributions == null || contributions.isEmpty()) {
            return null;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (UnifiedFactorScoringEngine.Contribution con : contributions) {
            if (con.percentile() != null) {
                out.put(con.factor().name(), con.percentile());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 按股票代码去重，保留名次靠前（即分数更高）的那条。
     *
     * <p>引擎对同一策略**不应**产出重复代码，但唯一键
     * {@code uk_css_day_strategy_stock} 会让"万一重复"从数据问题升级成整个请求 500。
     * 这里在写入前兜一道，并把重复如实记为 WARN——若将来引擎回归产生重复，
     * 表现是日志告警而不是全天打分失败。
     */
    private static List<UnifiedFactorScoringEngine.StockScore> dedupe(
            List<UnifiedFactorScoringEngine.StockScore> ordered) {
        Map<String, UnifiedFactorScoringEngine.StockScore> seen = new LinkedHashMap<>();
        for (UnifiedFactorScoringEngine.StockScore s : ordered) {
            UnifiedFactorScoringEngine.StockScore prev = seen.putIfAbsent(s.stockCode(), s);
            if (prev != null) {
                log.warn("全截面出现重复股票代码，已保留名次靠前者 code={} 保留分={} 丢弃分={}",
                        s.stockCode(), prev.finalScore(), s.finalScore());
            }
        }
        return new ArrayList<>(seen.values());
    }

    private static Comparator<UnifiedFactorScoringEngine.StockScore> byFinalScoreDesc() {
        return (a, b) -> {
            int c = Double.compare(b.finalScore(), a.finalScore());
            return c != 0 ? c : a.stockCode().compareTo(b.stockCode());
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
