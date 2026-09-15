package com.quant.repository;

import com.quant.entity.PredictionSnapshotItem;
import com.quant.entity.SelectorResultItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-R014-02（用户裁定 C「改实现」，2026-09-15）：**截面读取必须把行序显式钉到唯一键**。
 *
 * <p>背景（`rounds/R014_report.md`）：`rank_no` 不是唯一键（`selector_result_item` 的唯一约束是
 * `(resultId, stockCode, exchangeType)`），只按 `rank_no` 排序时并列行的相对次序由 DB 物理行序决定。
 * 审计构造出「同一多重集、仅把日内并列行序取逆」即让组均值改变 `1.1333e-3`、而 `rank_ic` 不变的实例。
 *
 * <p>本测试分两层，**能力不同、不得互相冒充**：
 * <ul>
 *   <li><b>契约层</b>（{@link #selectorReadsDeclareUniqueKeyTiebreak()} 等）：断言这些读取方法确实带
 *       显式 {@code @Query} 且 {@code ORDER BY} 落到唯一键 {@code id}。这是本项改动的**双向证明**承担者——
 *       改前这些方法没有 {@code @Query}（派生查询无从附加兜底键），必然失败。</li>
 *   <li><b>行为层</b>（{@link #tiedRankRowsComeBackInPrimaryKeyOrder()} 等）：断言并列行的实读次序为主键升序且可重复。
 *       注意其强度有限：H2 对并列行**碰巧**也按主键序返回，故它在改前同样可能通过；
 *       它的作用是回归护栏（防止将来有人改回只按 rank_no 排序或引入新的并列源），不是改动前的失败证据。</li>
 * </ul>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class CrossSectionRowOrderTest {

    private static final String UNIQUE_TIEBREAK = "I.ID";

    @Autowired
    private SelectorResultItemRepository selectorRepo;

    @Autowired
    private PredictionSnapshotItemRepository snapshotRepo;

    // ---------------------------------------------------------------- 契约层

    @Test
    void selectorReadsDeclareUniqueKeyTiebreak() throws Exception {
        assertOrderDeclared(
                SelectorResultItemRepository.class.getMethod("findByResultIdOrderByRankNoAsc", String.class),
                "ORDER BY I.RANKNO ASC, " + UNIQUE_TIEBREAK);
        assertOrderDeclared(
                SelectorResultItemRepository.class.getMethod(
                        "findByResultIdOrderByRankNoAsc", String.class, org.springframework.data.domain.Pageable.class),
                "ORDER BY I.RANKNO ASC, " + UNIQUE_TIEBREAK);
        assertOrderDeclared(
                SelectorResultItemRepository.class.getMethod("findByResultIdInOrderByRankNo", java.util.Collection.class),
                "ORDER BY I.RESULTID, I.RANKNO, " + UNIQUE_TIEBREAK);
    }

    @Test
    void snapshotReadsDeclareUniqueKeyTiebreak() throws Exception {
        assertOrderDeclared(
                PredictionSnapshotItemRepository.class.getMethod("findByRunIdOrderByRankNoAsc", String.class),
                "ORDER BY I.RANKNO ASC, " + UNIQUE_TIEBREAK);
        assertOrderDeclared(
                PredictionSnapshotItemRepository.class.getMethod("findByRunIdInOrderByRankNoAsc", List.class),
                "ORDER BY I.RUNID, I.RANKNO ASC, " + UNIQUE_TIEBREAK);
    }

    /** 分页变体必须自带显式 {@code countQuery}：否则计数语句要靠派生，钉序改动容易把计数一起带偏。 */
    @Test
    void pagedSelectorReadDeclaresExplicitCountQuery() throws Exception {
        Query q = SelectorResultItemRepository.class
                .getMethod("findByResultIdOrderByRankNoAsc", String.class, org.springframework.data.domain.Pageable.class)
                .getAnnotation(Query.class);
        assertThat(q).as("分页读取必须显式声明 @Query").isNotNull();
        assertThat(q.countQuery().toUpperCase(Locale.ROOT))
                .as("分页读取必须显式给出 countQuery")
                .contains("SELECT COUNT(I)");
    }

    private static void assertOrderDeclared(Method method, String expectedOrderBy) {
        Query q = method.getAnnotation(Query.class);
        assertThat(q)
                .as("读取方法 %s 必须显式声明 @Query 并把 ORDER BY 钉到唯一键（D-R014-02）", method.getName())
                .isNotNull();
        assertThat(q.value().toUpperCase(Locale.ROOT).replaceAll("\\s+", " "))
                .as("读取方法 %s 的 ORDER BY 必须落唯一键，防止并列行次序由 DB 物理行序决定", method.getName())
                .contains(expectedOrderBy);
    }

    // ---------------------------------------------------------------- 行为层

    @Test
    void tiedRankRowsComeBackInPrimaryKeyOrder() {
        String resultId = "DR01402TIE";
        selectorRepo.saveAllAndFlush(List.of(
                selectorItem(resultId, 1, "600001"),
                selectorItem(resultId, 2, "600002"),
                selectorItem(resultId, 2, "600003"),
                selectorItem(resultId, 2, "600004"),
                selectorItem(resultId, 3, "600005")));

        List<String> first = selectorRepo.findByResultIdOrderByRankNoAsc(resultId).stream()
                .map(SelectorResultItem::getStockCode).toList();
        assertThat(first).containsExactly("600001", "600002", "600003", "600004", "600005");

        List<String> second = selectorRepo.findByResultIdOrderByRankNoAsc(resultId).stream()
                .map(SelectorResultItem::getStockCode).toList();
        assertThat(second).as("重复读取不得改变并列行的相对次序").isEqualTo(first);
    }

    @Test
    void batchSelectorReadOrdersByResultThenRankThenId() {
        selectorRepo.saveAllAndFlush(List.of(
                selectorItem("R2", 1, "600011"),
                selectorItem("R1", 2, "600012"),
                selectorItem("R1", 1, "600013")));

        List<String> keys = selectorRepo.findByResultIdInOrderByRankNo(List.of("R1", "R2")).stream()
                .map(i -> i.getResultId() + "|" + i.getRankNo()).toList();
        assertThat(keys).containsExactly("R1|1", "R1|2", "R2|1");
    }

    @Test
    void tiedSnapshotRankRowsComeBackInPrimaryKeyOrder() {
        String runId = "PRDR01402TIE";
        snapshotRepo.saveAllAndFlush(List.of(
                snapshotItem(runId, 2, "600101"),
                snapshotItem(runId, 1, "600102"),
                snapshotItem(runId, 2, "600103")));

        List<String> codes = snapshotRepo.findByRunIdOrderByRankNoAsc(runId).stream()
                .map(PredictionSnapshotItem::getStockCode).toList();
        assertThat(codes).containsExactly("600102", "600101", "600103");
    }

    // ---------------------------------------------------------------- 构造

    private static SelectorResultItem selectorItem(String resultId, int rankNo, String stockCode) {
        SelectorResultItem item = new SelectorResultItem();
        item.setResultId(resultId);
        item.setRankNo(rankNo);
        item.setStrategyType("UNIFIED");
        item.setStockCode(stockCode);
        item.setExchangeType("SH");
        item.setStockName("测试" + stockCode);
        item.setFinalScore(1.0);
        item.setTrendScore(1.0);
        item.setMomentumScore(1.0);
        item.setVolumeScore(1.0);
        item.setRiskPenalty(0.0);
        item.setRet20(0.01);
        item.setRet60(0.02);
        item.setVolatility20(0.03);
        item.setMaxDrawdown60(0.04);
        item.setAvgAmount20(1.0e8);
        return item;
    }

    private static PredictionSnapshotItem snapshotItem(String runId, int rankNo, String stockCode) {
        PredictionSnapshotItem item = new PredictionSnapshotItem();
        item.setRunId(runId);
        item.setTradeDate(LocalDate.of(2026, 9, 11));
        item.setHorizon(2);
        item.setTrack("RULE_SCORE");
        item.setPublishVersion(1);
        item.setStockCode(stockCode);
        item.setExchangeType("SH");
        item.setStockName("测试" + stockCode);
        item.setRankNo(rankNo);
        item.setScore(50.0);
        item.setScoreType("RULE_SCORE");
        return item;
    }
}
