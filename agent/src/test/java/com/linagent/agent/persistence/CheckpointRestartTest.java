package com.linagent.agent.persistence;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨 agent 实例的会话记忆恢复（模拟应用重启）：
 * 两个独立 ReactAgent 实例 + 各自独立的 saver 实例（同一 PG）+ 同一 threadId，
 * 第二个实例执行时收到的 Prompt 必须包含第一轮的用户消息（stub ChatModel 记录收到的消息）。
 *
 * 对照组 memorySaverDoesNotRestoreAcrossInstances 证明 MemorySaver（进程内 map）
 * 换实例即失忆——PG saver 的存在意义。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckpointRestartTest {

    private static final String SECRET = "记住暗号是苹果";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;

    @BeforeAll
    void initDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        dataSource = ds;
    }

    @Test
    void memorySurvivesAcrossSaverAndAgentInstances() throws Exception {
        // 两个独立 saver 实例：各自持有独立进程内缓存，只能靠 PG 行共享（模拟重启后的新进程）
        BaseCheckpointSaver saver1 = PostgresSaver.builder().datasource(dataSource).build();
        BaseCheckpointSaver saver2 = PostgresSaver.builder().datasource(dataSource).build();
        String threadId = "restart-" + System.nanoTime();

        RecordingModel model1 = new RecordingModel();
        RecordingModel model2 = new RecordingModel();

        agent(model1, saver1).stream(new UserMessage(SECRET), config(threadId)).blockLast();

        // PG 表里真实落了 checkpoint 行（GraphCheckpoint 随 GraphThread 级联建表）
        assertThat(checkpointRowCount(threadId)).isGreaterThan(0);

        agent(model2, saver2).stream(new UserMessage("暗号是什么？"), config(threadId)).blockLast();

        // 重启后的实例能看到第一轮消息历史
        assertThat(model2.receivedTexts())
            .anySatisfy(t -> assertThat(t).contains(SECRET));
    }

    @Test
    void memorySaverDoesNotRestoreAcrossInstances() throws Exception {
        BaseCheckpointSaver saver1 = new MemorySaver();
        BaseCheckpointSaver saver2 = new MemorySaver();
        String threadId = "memory-contrast-" + System.nanoTime();

        RecordingModel model1 = new RecordingModel();
        RecordingModel model2 = new RecordingModel();

        agent(model1, saver1).stream(new UserMessage(SECRET), config(threadId)).blockLast();
        agent(model2, saver2).stream(new UserMessage("暗号是什么？"), config(threadId)).blockLast();

        // 进程内 map：第二个 saver 实例拿不到第一轮历史（对照组）
        assertThat(model2.receivedTexts())
            .noneSatisfy(t -> assertThat(t).contains(SECRET));
    }

    private ReactAgent agent(ChatModel model, BaseCheckpointSaver saver) {
        return ReactAgent.builder()
            .name("restart-test-agent")
            .model(model)
            .systemPrompt("你是测试助手")
            .saver(saver)
            .build();
    }

    private RunnableConfig config(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private long checkpointRowCount(String threadId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT count(*) FROM graphcheckpoint c
                 JOIN graphthread t ON c.thread_id = t.thread_id
                 WHERE t.thread_name = '%s'
                 """.formatted(threadId))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 最小 ChatModel 桩：记录收到的 Prompt 消息，应答固定文本 */
    static class RecordingModel implements ChatModel {

        private final List<String> texts = new CopyOnWriteArrayList<>();

        List<String> receivedTexts() {
            return new ArrayList<>(texts);
        }

        private void record(Prompt prompt) {
            prompt.getInstructions().forEach(m -> {
                if (m.getText() != null) {
                    texts.add(m.getText());
                }
            });
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            record(prompt);
            return respond();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            record(prompt);
            return reactor.core.publisher.Flux.just(respond());
        }

        private ChatResponse respond() {
            AssistantMessage msg = AssistantMessage.builder()
                .content("好的")
                .properties(Map.of())
                .build();
            return new ChatResponse(List.of(new Generation(msg)));
        }
    }
}
