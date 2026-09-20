package com.javaagent.agent.persistence;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话删除的 checkpoint 级联清理（Errata 3）：
 * 用真实 PostgresSaver 落 checkpoint（建表/写入走 saver 自身逻辑），再验证按会话清理。
 */
@DataJdbcTest(properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__init.sql"
})
@Import({JdbcConverterConfig.class, CheckpointCleaner.class})
@Testcontainers
class CheckpointCleanerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    @Autowired
    CheckpointCleaner cleaner;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @Test
    void deleteByConversationIdRemovesAllThreadsOfThatConversation() throws Exception {
        // saver 构造即 CREATE IF NOT EXISTS 建表（幂等）
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource).build();

        Long convId = 5L;
        // 目标会话：原始线程 + 压缩换代线程（conv-5 / conv-5-v2）
        saveCheckpoint(saver, "conv-" + convId);
        saveCheckpoint(saver, "conv-" + convId + "-v2");
        // 无关会话（对照组，不得误删）
        saveCheckpoint(saver, "conv-6");
        saveCheckpoint(saver, "conv-55");

        assertThat(checkpointCount("conv-5")).isEqualTo(1);
        assertThat(checkpointCount("conv-5-v2")).isEqualTo(1);

        cleaner.deleteByConversationId(convId);

        assertThat(checkpointCount("conv-5")).isZero();
        assertThat(checkpointCount("conv-5-v2")).isZero();
        // thread 行本身也被删除（后续同 threadId 会话将干净起步）
        assertThat(threadCount("conv-5")).isZero();
        assertThat(threadCount("conv-5-v2")).isZero();
        assertThat(checkpointCount("conv-6")).isEqualTo(1);
        assertThat(checkpointCount("conv-55")).isEqualTo(1);
    }

    @Test
    void deleteIsIdempotentAndSafeForUnknownConversation() {
        cleaner.deleteByConversationId(404L);
        cleaner.deleteByConversationId(404L);
    }

    private void saveCheckpoint(PostgresSaver saver, String threadId) throws Exception {
        saver.put(
            RunnableConfig.builder().threadId(threadId).build(),
            Checkpoint.builder()
                .id(UUID.randomUUID().toString())
                .nodeId("agent_model")
                .nextNodeId("__END__")
                .state(Map.of("messages",
                    List.of(org.springframework.ai.chat.messages.UserMessage.builder()
                        .text("记住暗号是苹果").build())))
                .build());
    }

    private long checkpointCount(String threadId) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM graphcheckpoint c
            JOIN graphthread t ON c.thread_id = t.thread_id
            WHERE t.thread_name = ?
            """, Long.class, threadId);
        return count == null ? 0 : count;
    }

    private long threadCount(String threadId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM graphthread WHERE thread_name = ?", Long.class, threadId);
        return count == null ? 0 : count;
    }
}
