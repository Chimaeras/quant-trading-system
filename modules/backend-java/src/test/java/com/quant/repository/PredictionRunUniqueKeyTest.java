package com.quant.repository;

import com.quant.entity.PredictionRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R31-P1-1：幂等键唯一性必须落在 (request_id, track) 上。
 *
 * <p>用 H2 建表（禁用 Flyway、H2 方言）跑真实 INSERT，验证实体映射声明的唯一约束语义：
 * 一次 generate 的两条 track 可共用同一 requestId；同一 (requestId, track) 不得重复；
 * 无 requestId 的日常重复发布（requestId 为 NULL）不受影响——MySQL/InnoDB 唯一索引中
 * NULL 互不相等，这是「每天重复发布」不被误拦的前提。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class PredictionRunUniqueKeyTest {

    @Autowired
    private PredictionRunRepository repo;

    private static PredictionRun run(String requestId, String track) {
        return run(requestId, track, 1);
    }

    private static PredictionRun run(String requestId, String track, int publishVersion) {
        PredictionRun run = new PredictionRun();
        run.setRunId("PRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        run.setTradeDate(LocalDate.of(2026, 9, 11));
        run.setHorizon(2);
        run.setTrack(track);
        run.setPublishVersion(publishVersion);
        run.setStatus(PredictionRun.STATUS_PUBLISHED);
        run.setRequestId(requestId);
        run.setItemCount(0);
        return run;
    }

    @Test
    void twoTracksMayShareOneRequestId() {
        repo.saveAndFlush(run("req-r31", PredictionRun.TRACK_RULE_SCORE));
        repo.saveAndFlush(run("req-r31", PredictionRun.TRACK_PROBABILITY));

        assertThat(repo.findByRequestId("req-r31")).hasSize(2);
    }

    @Test
    void duplicateTrackForSameRequestIdIsRejected() {
        repo.saveAndFlush(run("req-dup", PredictionRun.TRACK_RULE_SCORE, 1));

        // publishVersion 故意不同：(trade_date,horizon,track,publish_version) 放行，
        // 只有 (request_id, track) 能拦住——即幂等键真的在起作用。
        assertThatThrownBy(() -> repo.saveAndFlush(run("req-dup", PredictionRun.TRACK_RULE_SCORE, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullRequestIdStillAllowsRepeatedPublish() {
        // 不带 requestId 的发布靠 publish_version 递增区分，不能被唯一键拦住。
        repo.saveAndFlush(run(null, PredictionRun.TRACK_RULE_SCORE, 1));
        repo.saveAndFlush(run(null, PredictionRun.TRACK_RULE_SCORE, 2));

        assertThat(repo.findAll()).hasSize(2);
    }
}
