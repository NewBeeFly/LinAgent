package com.linagent.web.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@Testcontainers
class FlywayMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesThreeTables() {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name in ('conversation','turn','message')",
            Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void migrationCreatesCheckpointTables() {
        // V2__checkpoint.sql：PostgresSaver 的存储表（checkpoint 级联删除依赖 FK）
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name in ('graphthread','graphcheckpoint')",
            Integer.class);
        assertThat(count).isEqualTo(2);

        String fk = jdbcTemplate.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'fk_thread'",
            String.class);
        assertThat(fk).contains("ON DELETE CASCADE");
    }

    @Test
    void messageTypeCheckContainsAllTypes() {
        String types = jdbcTemplate.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'message_msg_type_check'",
            String.class);
        assertThat(types).contains("USER", "THINKING", "TEXT", "TOOL_CALL", "TOOL_RESULT", "ERROR", "SUMMARY");
    }

    @Test
    void compactionAnchorColumnAddedByV3() {
        // V3__compaction_anchor.sql：压缩锚点（已摘要到哪轮），估算基准与给模型的记忆同量纲
        String column = jdbcTemplate.queryForObject("""
            select data_type || ':' || is_nullable || ':' || column_default
            from information_schema.columns
            where table_name = 'conversation' and column_name = 'compacted_turn_seq'
            """, String.class);
        assertThat(column).isEqualTo("integer:NO:0");
    }
}
