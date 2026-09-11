package com.quant.repository;

import com.quant.entity.LabelSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LabelSnapshotRepository extends JpaRepository<LabelSnapshot, Long> {

    Optional<LabelSnapshot> findByLabelVersionAndSignalDateAndStockCodeAndExchangeTypeAndHorizon(
            String labelVersion, LocalDate signalDate, String stockCode, String exchangeType, Integer horizon);

    /**
     * R31-P2-1：单个信号日「已存在标签」的 (code, exchange, horizon) 三元组。
     *
     * <p>标签生成原实现按「股票 × horizon」逐条查存在性（一年约 5000×3×250 ≈ 375 万次 SELECT）。
     * 改为按日一次取回该日全部已有键、循环内做集合命中判断：每天的查询数从约 1.5 万降到 1。
     */
    @Query("SELECT l.stockCode, l.exchangeType, l.horizon FROM LabelSnapshot l " +
            "WHERE l.labelVersion = :version AND l.signalDate = :signalDate")
    List<Object[]> findExistingKeysByVersionAndSignalDate(
            @Param("version") String labelVersion, @Param("signalDate") LocalDate signalDate);

    @Query("SELECT COUNT(l) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    long countByVersion(@Param("version") String labelVersion);

    @Query("SELECT COUNT(l) FROM LabelSnapshot l WHERE l.labelVersion = :version AND l.execStatus = :status")
    long countByVersionAndExecStatus(@Param("version") String labelVersion, @Param("status") String execStatus);

    @Query("SELECT COUNT(DISTINCT l.signalDate) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    long countDistinctSignalDates(@Param("version") String labelVersion);

    @Query("SELECT MIN(l.signalDate) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    Optional<LocalDate> findMinSignalDate(@Param("version") String labelVersion);

    @Query("SELECT MAX(l.signalDate) FROM LabelSnapshot l WHERE l.labelVersion = :version")
    Optional<LocalDate> findMaxSignalDate(@Param("version") String labelVersion);

    @Query("SELECT l FROM LabelSnapshot l WHERE l.labelVersion = :version ORDER BY l.signalDate DESC")
    List<LabelSnapshot> findLatestByVersion(@Param("version") String labelVersion, Pageable pageable);

    @Query("SELECT l FROM LabelSnapshot l WHERE l.labelVersion = :version AND l.horizon = :horizon " +
            "AND l.signalDate BETWEEN :start AND :end AND l.execStatus = 'FILLED' " +
            "ORDER BY l.signalDate ASC")
    List<LabelSnapshot> findFilledByVersionAndHorizonBetween(
            @Param("version") String labelVersion,
            @Param("horizon") int horizon,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    /** V2-MP-022：拉取某 label_version + horizon 在区间内全部标签（含拒单/停牌/退市各执行状态），
     *  供因子研究 EXECUTABLE 口径 join 与拒绝漏斗统计（不做删除净化，保留每类 reject）。 */
    @Query("SELECT l FROM LabelSnapshot l WHERE l.labelVersion = :version AND l.horizon = :horizon " +
            "AND l.signalDate BETWEEN :start AND :end ORDER BY l.signalDate ASC, l.stockCode ASC")
    List<LabelSnapshot> findByLabelVersionAndHorizonAndSignalDateBetween(
            @Param("version") String labelVersion,
            @Param("horizon") int horizon,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    /** V2-MP-023：测试窗标签总数（全部执行状态），作滚动 WFO coverage 分母。 */
    @Query("SELECT COUNT(l) FROM LabelSnapshot l WHERE l.labelVersion = :version AND l.horizon = :horizon " +
            "AND l.signalDate BETWEEN :start AND :end")
    long countByLabelVersionAndHorizonAndSignalDateBetween(
            @Param("version") String labelVersion,
            @Param("horizon") int horizon,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);
}
