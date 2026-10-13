package com.quant.repository;

import com.quant.entity.SecurityMasterHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * security_master_history 的「真实 JPA/SQL」PIT 语义测试（QP-009 防护，替换原同义反复的 mock 版）。
 *
 * <p>用 H2 内存库建表（无主库连接、禁用 Flyway、H2 方言），直接执行 JPA 仓库的真实查询，
 * 从而能抓住「删除 valid_to > :asOf 子句」这类回归——mock 版测不到。
 *
 * <p>核心不变量：某日生效与否仅由 `valid_from &lt;= as_of &lt; valid_to`（null 即无限期）决定，
 * 未来才生效 / 已失效的记录都不算"该日生效"。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        // 主配置 application.yml 把 hibernate.dialect 固定成 MySQLDialect，会在 H2 上生成
        // `engine=InnoDB` 等 MySQL DDL 导致建表失败；覆盖回 H2Dialect 才能跑真实 JPA/SQL 语义。
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class SecurityMasterHistoryPitTest {

    @Autowired
    private SecurityMasterHistoryRepository repo;

    private static final LocalDate AS_OF = LocalDate.of(2023, 6, 30);

    @Test
    void singleQueryReturnsTheRecordEffectiveAtAsOf() {
        save("000001", "SZSE", "旧名称", LocalDate.of(2010, 1, 1), LocalDate.of(2024, 1, 1));
        save("000001", "SZSE", "新名称", LocalDate.of(2024, 1, 1), null);

        Optional<SecurityMasterHistory> r = repo.findEffectiveAt("000001", "SZSE", AS_OF);
        // 改名前的 as_of 应命中旧名称记录，不泄漏未来改名后的名称
        assertThat(r).isPresent();
        assertThat(r.get().getStockName()).isEqualTo("旧名称");
        assertThat(r.get().getValidTo()).isAfter(AS_OF);
    }

    @Test
    void singleQueryExcludesFutureEffectiveRecord() {
        // 2024 年才上市/生效 → as_of=2023-06-30 不应命中
        save("000002", "SZSE", "未来上市", LocalDate.of(2024, 3, 1), null);
        assertThat(repo.findEffectiveAt("000002", "SZSE", AS_OF)).isEmpty();
    }

    @Test
    void singleQueryExcludesAlreadyExpiredRecord() {
        // valid_to 早于 as_of（已退市/失效）→ 不应命中
        save("000003", "SZSE", "已退市", LocalDate.of(2010, 1, 1), LocalDate.of(2023, 1, 1));
        assertThat(repo.findEffectiveAt("000003", "SZSE", AS_OF)).isEmpty();
    }

    @Test
    void boundaryValidFromInclusiveAndValidToExclusive() {
        save("000005", "SZSE", "当天起始", AS_OF, null);          // valid_from == as_of → 含
        save("000006", "SZSE", "当天失效", LocalDate.of(2010, 1, 1), AS_OF); // valid_to == as_of → 不含
        assertThat(repo.findEffectiveAt("000005", "SZSE", AS_OF)).isPresent();
        assertThat(repo.findEffectiveAt("000006", "SZSE", AS_OF)).isEmpty();
    }

    @Test
    void batchQueryReturnsOnlyRecordsActiveAtAsOf() {
        save("000001", "SZSE", "有效", LocalDate.of(2010, 1, 1), LocalDate.of(2024, 1, 1));
        save("000002", "SZSE", "未来上市", LocalDate.of(2024, 3, 1), null);
        save("000003", "SZSE", "已退市", LocalDate.of(2010, 1, 1), LocalDate.of(2023, 1, 1));
        save("000004", "SZSE", "无限期", LocalDate.of(2020, 1, 1), null);
        save("000005", "SZSE", "当天起始", AS_OF, null);

        List<SecurityMasterHistory> active = repo.findAllEffectiveAt(AS_OF);
        List<String> codes = active.stream().map(SecurityMasterHistory::getStockCode).toList();
        assertThat(codes).contains("000001", "000004", "000005");
        assertThat(codes).doesNotContain("000002", "000003");
        // 未来上市（000002）与已退市（000003）不得进入历史时点股票池 → 无前视/幸存者偏差泄漏
        for (SecurityMasterHistory h : active) {
            assertThat(h.getValidFrom()).isBeforeOrEqualTo(AS_OF);
            assertThat(h.getValidTo() == null || h.getValidTo().isAfter(AS_OF)).isTrue();
        }
    }

    @Test
    void countEffectiveAtMatchesBatchSize() {
        save("000001", "SZSE", "有效", LocalDate.of(2010, 1, 1), LocalDate.of(2024, 1, 1));
        save("000004", "SZSE", "无限期", LocalDate.of(2020, 1, 1), null);
        assertThat(repo.countAllEffectiveAt(AS_OF)).isEqualTo(2);
    }

    @Test
    void unverifiedQueriesRejectBootstrapAndUnknownReliabilityWithinEffectiveDates() {
        for (int i = 1; i <= 4; i++) {
            SecurityMasterHistory h = new SecurityMasterHistory();
            h.setStockCode("00000" + i); h.setExchangeType("SZSE"); h.setStockName("测试" + i);
            h.setListDate(LocalDate.of(2010, 1, 1));
            h.setValidFrom(i == 4 ? AS_OF.plusDays(1) : AS_OF.minusDays(1));
            h.setSource(i == 2 ? "current_snapshot" : "official_source");
            h.setReliability(i == 3 ? null : i == 4 ? "limited" : "official");
            repo.save(h);
        }
        repo.flush();
        assertThat(repo.countUnverified()).isEqualTo(3);
        assertThat(repo.countUnverifiedEffectiveAt(AS_OF)).isEqualTo(2);
    }

    private void save(String code, String ex, String name, LocalDate validFrom, LocalDate validTo) {
        SecurityMasterHistory h = new SecurityMasterHistory();
        h.setStockCode(code);
        h.setExchangeType(ex);
        h.setStockName(name);
        h.setValidFrom(validFrom);
        h.setValidTo(validTo);
        h.setSource("test");
        repo.save(h);
    }
}
