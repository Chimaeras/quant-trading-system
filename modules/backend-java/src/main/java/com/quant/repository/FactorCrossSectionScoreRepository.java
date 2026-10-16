package com.quant.repository;

import com.quant.entity.FactorCrossSectionScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 全截面分数仓储（研究用）。
 *
 * <p>读取侧的主要消费者是因子研究：需要"某日某策略的**全部**候选分数"，
 * 才能算 Rank IC。排序显式钉到 {@code crossSectionRank} 再以 {@code id} 兜底，
 * 理由与 {@code SelectorResultItemRepository} 的 {@code D-R014-02} 一致：
 * 截面行序不稳会让分桶结果漂移。
 */
public interface FactorCrossSectionScoreRepository
        extends JpaRepository<FactorCrossSectionScore, Long> {

    /** 某日某策略的全截面，按全截面名次升序（并列按主键，保证可复现）。 */
    @Query("SELECT s FROM FactorCrossSectionScore s "
            + "WHERE s.tradeDate = :tradeDate AND s.strategyCode = :strategyCode "
            + "ORDER BY s.crossSectionRank ASC, s.id ASC")
    List<FactorCrossSectionScore> findCrossSection(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("strategyCode") String strategyCode);

    /** 区间内某策略的全部截面，按日升序（IC 时间序列的主入口）。 */
    @Query("SELECT s FROM FactorCrossSectionScore s "
            + "WHERE s.strategyCode = :strategyCode "
            + "AND s.tradeDate BETWEEN :startDate AND :endDate "
            + "ORDER BY s.tradeDate ASC, s.crossSectionRank ASC, s.id ASC")
    List<FactorCrossSectionScore> findCrossSectionRange(
            @Param("strategyCode") String strategyCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    long countByTradeDateAndStrategyCode(LocalDate tradeDate, String strategyCode);

    /**
     * 幂等重跑：删掉该 (日, 策略) 已有行再整体重写。
     *
     * <p>用删除+重写而不是 upsert，是因为重跑时候选集可能变化
     * （退市、停牌、新上市都会改变全截面成员），残留旧行会让 IC 样本混入已不在样本中的股票。
     *
     * <p><b>{@code flushAutomatically = true} 是必需的，不是优化</b>：
     * 本方法在 {@link com.quant.service.scoring.factor.FactorCrossSectionWriter#replace}
     * 里紧跟着一次 {@code saveAll}，而 Hibernate 默认把 INSERT 攒进 ActionQueue，
     * bulk delete 却直接发 SQL。若不在删除前 flush，实际到达数据库的语句顺序可能是
     * <b>INSERT 先于 DELETE</b>，于是重跑同一 (日, 策略) 时插入撞上尚未删除的旧行，
     * 报 {@code Duplicate entry ... for key 'uk_css_day_strategy_stock'}。
     * 该缺陷已实测复现（2026-10-15 首次真实运行）：首次运行成功、第二次必失败。
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM FactorCrossSectionScore s "
            + "WHERE s.tradeDate = :tradeDate AND s.strategyCode = :strategyCode")
    int deleteCrossSection(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("strategyCode") String strategyCode);
}
