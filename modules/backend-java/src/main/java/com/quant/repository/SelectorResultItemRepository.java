package com.quant.repository;

import com.quant.entity.SelectorResultItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Set;

public interface SelectorResultItemRepository extends JpaRepository<SelectorResultItem, Long> {

    /**
     * 读取一次选股结果的全部明细，**名次升序、并列时按主键升序**（`D-R014-02`）。
     *
     * <p>`rank_no` **不是唯一键**：唯一约束落在 `(resultId, stockCode, exchangeType)` 上，名次只由打分排序产生。
     * 现有写入方都用 `rank = 1; rank++` 计数器，故真实数据里通常无并列；但「通常」不是契约——
     * 只按 `rank_no` 排序时，并列行的相对次序由 DB 物理行序决定，而下游（分桶等）会读这个次序，
     * 于是同一多重集可能算出不同的组均值（R014 审计已构造出 `maxdiff = 1.1333e-3` 的实测差异）。
     * 因此这里把次序**显式钉到唯一键**：`ORDER BY rankNo ASC, id ASC`。
     */
    @Query("SELECT i FROM SelectorResultItem i WHERE i.resultId = :resultId ORDER BY i.rankNo ASC, i.id ASC")
    List<SelectorResultItem> findByResultIdOrderByRankNoAsc(@Param("resultId") String resultId);

    /** 同上（分页变体，`countQuery` 显式给出以免依赖派生计数语句）。 */
    @Query(
            value = "SELECT i FROM SelectorResultItem i WHERE i.resultId = :resultId ORDER BY i.rankNo ASC, i.id ASC",
            countQuery = "SELECT COUNT(i) FROM SelectorResultItem i WHERE i.resultId = :resultId"
    )
    Page<SelectorResultItem> findByResultIdOrderByRankNoAsc(@Param("resultId") String resultId, Pageable pageable);

    void deleteByResultId(String resultId);
    Optional<SelectorResultItem> findByResultIdAndStockCodeAndExchangeType(String resultId, String stockCode, String exchangeType);
    long countByResultId(String resultId);

    @Query("select concat(i.stockCode, '|', i.exchangeType) from SelectorResultItem i where i.resultId = :resultId")
    Set<String> findStockKeysByResultId(@Param("resultId") String resultId);

    /**
     * 批量读取多次结果的明细，按 `(resultId, rankNo, id)` 升序（`D-R014-02`，理由见上文单结果变体）。
     *
     * <p>这是因子研究/打分回测/组合回测等**截面读取**的主入口；截面行序一旦不稳，
     * 分桶（下标等分）的桶成员就会变，`rank_ic` 不变而组均值变——正是 R014 审计构造出的现象。
     */
    @Query("SELECT i FROM SelectorResultItem i WHERE i.resultId IN :resultIds ORDER BY i.resultId, i.rankNo, i.id")
    List<SelectorResultItem> findByResultIdInOrderByRankNo(@Param("resultIds") Collection<String> resultIds);
}
