package com.quant.repository;

import com.quant.entity.PredictionRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PredictionRunRepository extends JpaRepository<PredictionRun, Long> {

    Optional<PredictionRun> findByRunId(String runId);

    /** 同 requestId 同日期同 horizon 查重（幂等，V2-MP-030）。 */
    Optional<PredictionRun> findFirstByRequestIdAndTradeDateAndHorizon(
            String requestId, LocalDate tradeDate, Integer horizon);

    /** 一次 generate 会生成 RULE_SCORE + PROBABILITY 两条 run，共用同一 requestId。 */
    List<PredictionRun> findByRequestId(String requestId);

    List<PredictionRun> findByTradeDateAndHorizonOrderByPublishVersionDescTrackAsc(
            LocalDate tradeDate, Integer horizon);

    @Query("SELECT COALESCE(MAX(p.publishVersion), 0) FROM PredictionRun p "
            + "WHERE p.tradeDate = :tradeDate AND p.horizon = :horizon AND p.track = :track")
    int findMaxPublishVersion(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("horizon") Integer horizon,
            @Param("track") String track);

    /** 某日某 horizon 各轨道最新 publish_version 的全部 run（可能多 track）。 */
    @Query("SELECT p FROM PredictionRun p WHERE p.tradeDate = :tradeDate AND p.horizon = :horizon "
            + "AND p.publishVersion = ("
            + "  SELECT MAX(p2.publishVersion) FROM PredictionRun p2 "
            + "  WHERE p2.tradeDate = p.tradeDate AND p2.horizon = p.horizon AND p2.track = p.track"
            + ") ORDER BY p.track")
    List<PredictionRun> findLatestByTradeDateAndHorizon(
            @Param("tradeDate") LocalDate tradeDate,
            @Param("horizon") Integer horizon);

    List<PredictionRun> findByTradeDateOrderByHorizonAscTrackAscPublishVersionDesc(LocalDate tradeDate);

    /** V2-MP-012：某日某 horizon 某轨道全部 publish_version（日报版本选择器）。 */
    List<PredictionRun> findByTradeDateAndHorizonAndTrackOrderByPublishVersionDesc(
            LocalDate tradeDate, Integer horizon, String track);

    /** V2-MP-012：全部预测快照的最新信号日（日报 tradeDate 省略时解析）。 */
    @Query("SELECT MAX(p.tradeDate) FROM PredictionRun p")
    Optional<LocalDate> findMaxTradeDate();
}
