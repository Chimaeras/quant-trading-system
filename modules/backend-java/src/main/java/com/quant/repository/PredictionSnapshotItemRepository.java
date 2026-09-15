package com.quant.repository;

import com.quant.entity.PredictionSnapshotItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PredictionSnapshotItemRepository extends JpaRepository<PredictionSnapshotItem, Long> {

    /**
     * 读取一次发布的全部明细，**名次升序、并列时按主键升序**（`D-R014-02`，同族读取同一处理）。
     *
     * <p>`rank_no` 不在唯一约束内（唯一键是 `trade_date/horizon/track/publish_version/stock_code/exchange_type`），
     * 只按它排序时并列行的相对次序由 DB 物理行序决定；组合回测按该次序取 top-N 选股，
     * 所以这里同样把次序钉到唯一键 `id`。
     */
    @Query("SELECT i FROM PredictionSnapshotItem i WHERE i.runId = :runId ORDER BY i.rankNo ASC, i.id ASC")
    List<PredictionSnapshotItem> findByRunIdOrderByRankNoAsc(@Param("runId") String runId);

    /** 同上（多次发布批量读取变体）。 */
    @Query("SELECT i FROM PredictionSnapshotItem i WHERE i.runId IN :runIds ORDER BY i.runId, i.rankNo ASC, i.id ASC")
    List<PredictionSnapshotItem> findByRunIdInOrderByRankNoAsc(@Param("runIds") List<String> runIds);
}
