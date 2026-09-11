package com.quant.entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R31-P1-1 契约锁定：prediction_run 的幂等键必须是 (request_id, track)。
 *
 * <p>一次 generate 会写 RULE_SCORE + PROBABILITY 两条 run 且共用同一 requestId，
 * 若唯一键退回单列 request_id，第二条 INSERT 必然冲突 → 带 requestId 的发布恒定 409。
 * 单测无法验证真实索引，故此处锁定「实体映射」与「Flyway 迁移」两处声明，任一处回退即失败。
 */
class PredictionRunSchemaTest {

    @Test
    void entityDeclaresCompositeRequestIdTrackUniqueConstraint() {
        Table table = PredictionRun.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();

        boolean compositePresent = Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .anyMatch(cols -> cols.length == 2
                        && "request_id".equals(cols[0])
                        && "track".equals(cols[1]));
        assertThat(compositePresent)
                .as("prediction_run 须声明 (request_id, track) 复合唯一约束")
                .isTrue();

        assertThat(Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .noneMatch(cols -> cols.length == 1 && "request_id".equals(cols[0])))
                .as("不得存在单列 request_id 唯一约束（会与双轨共用 requestId 的契约冲突）")
                .isTrue();
    }

    @Test
    void migrationReplacesSingleColumnUniqueKeyWithComposite() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V22__prediction_run_request_id_track.sql")) {
            assertThat(in).as("V22 迁移必须存在").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("DROP KEY uk_prediction_run_request_id");
            assertThat(sql).contains("uk_prediction_run_request_track (request_id, track)");
        }
    }
}
