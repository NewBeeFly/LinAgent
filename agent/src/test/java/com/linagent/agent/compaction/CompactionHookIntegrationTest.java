package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.linagent.agent.skills.ResidentPromptBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根治终验（spec §7）：真实 ReactAgent + PostgresSaver + 低阈值 hook 连续四轮，
 * 每个模型 Prompt 中「此前对话摘要：」SystemMessage 至多 1 份（旧实现逐轮 append，
 * 第 N 轮输入含 N 份摘要快照），压缩触发后的 Prompt 恰 1 份。
 *
 * 轮次编排（threshold=5 必触发、keepTurns=2）：cutoff = 倒数第 2 个 UserMessage 的
 * 下标，>0 需至少 3 条 UserMessage——第 1/2 轮无可切割区放行（0 份摘要），第 3/4 轮
 * 各压缩一次。两次连续压缩是鉴别 REPLACE 与逐轮叠加的关键：若摘要被 append 进
 * checkpoint state（旧 bug），第 4 轮 Prompt 会出现 2 份摘要 → 断言失败。
 *
 * REPLACE 落库证据：sink 收到的 SummaryContext.messagesAfter < messagesBefore
 * （真实内存列表原地收缩），且第 4 轮 Prompt 条数 < 无压缩反事实（1 system + 7 历史 = 8）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactionHookIntegrationTest {

    private static final String FIXED_SUMMARY = "集成摘要：用户连续提问并得到回答。";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;

    private final List<List<Message>> streamedPrompts = new CopyOnWriteArrayList<>();
    private final List<CompactionSummarySink.SummaryContext> summaries = new CopyOnWriteArrayList<>();

    @BeforeAll
    void initDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        dataSource = ds;
    }

    @Test
    void compactionReplacesHistoryAndKeepsSingleSummaryAcrossTurns() throws Exception {
        BaseCheckpointSaver saver = PostgresSaver.builder().datasource(dataSource).build();
        String threadId = "compact-" + System.nanoTime();

        RecordingModel model = new RecordingModel();
        // 摘要请求前缀须与主调用 system prompt 对齐（cache-safe），本桩不校验内容、仅占位
        ResidentPromptBuilder promptBuilder = org.mockito.Mockito.mock(ResidentPromptBuilder.class);
        org.mockito.Mockito.when(promptBuilder.build()).thenReturn("stub-system-prompt");
        SummarizingModelHook hook = new SummarizingModelHook(
            model, summaries::add, promptBuilder, 5, 2, 2, Duration.ofSeconds(60)); // 阈值 5 tokens：必触发；保 2 轮

        ReactAgent agent = ReactAgent.builder()
            .name("compact-test-agent")
            .model(model)
            .systemPrompt("你是测试助手")
            .hooks(List.of(hook))
            .saver(saver)
            .build();

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        for (int round = 1; round <= 4; round++) {
            agent.stream(new UserMessage("第" + round + "轮问题，" + "x".repeat(60)), config).blockLast();
        }

        // 无工具 → 每轮恰好 1 次模型调用（宽松下限防 SAA 轮内多次调用改变形态）
        assertThat(streamedPrompts).hasSizeGreaterThanOrEqualTo(4);

        // 任何 Prompt 的摘要 SystemMessage 至多 1 份——旧实现第 4 轮叠加出 2 份，此处必炸
        for (List<Message> prompt : streamedPrompts) {
            long summaryCount = summaryCount(prompt);
            assertThat(summaryCount)
                .as("摘要 SystemMessage 不得叠加，实际 %d：%s", summaryCount, prompt)
                .isLessThanOrEqualTo(1);
        }
        // 压缩已触发（第 3 轮起）且触发后的每个 Prompt 恰 1 份摘要
        assertThat(summaryCount(streamedPrompts.get(streamedPrompts.size() - 1))).isEqualTo(1);
        long compactedPrompts = streamedPrompts.stream().mapToLong(this::summaryCount)
            .filter(count -> count == 1).count();
        assertThat(compactedPrompts).as("压缩触发过的 Prompt 数").isGreaterThanOrEqualTo(2);

        // 第 3/4 轮各压缩一次（两次连续压缩是 REPLACE 与 append 的鉴别点）
        assertThat(summaries).hasSize(2);
        // REPLACE 落库：压缩永不使列表变长（append 语义下第 3 轮 5→6）。首轮 5→5 持平是
        // 本 fixture 的边界形态：可摘区仅 [A1]（1 条）↔ 1 条摘要消息、firstUser 保留
        for (CompactionSummarySink.SummaryContext ctx : summaries) {
            assertThat(ctx.messagesAfter())
                .as("压缩不得使消息列表变长 %s", ctx)
                .isLessThanOrEqualTo(ctx.messagesBefore());
        }
        // 末次压缩（历史更长、可摘区更大）必须严格收缩：7→5
        CompactionSummarySink.SummaryContext lastCtx = summaries.get(summaries.size() - 1);
        assertThat(lastCtx.messagesAfter())
            .as("末次压缩应严格收缩 %s", lastCtx)
            .isLessThan(lastCtx.messagesBefore());

        // 无压缩反事实：1 system + 7 条历史（U/A ×3 + U4）= 8；REPLACE 后明显更小
        List<Message> last = streamedPrompts.get(streamedPrompts.size() - 1);
        assertThat(last.size()).as("末轮 Prompt 条数应远小于无压缩累积").isLessThanOrEqualTo(6);
    }

    private long summaryCount(List<Message> prompt) {
        return prompt.stream()
            .filter(m -> m instanceof SystemMessage)
            .filter(m -> m.getText() != null && m.getText().startsWith(SummarizingModelHook.SUMMARY_PREFIX))
            .count();
    }

    /** 最小 ChatModel 桩：stream（对话）记录 Prompt 并应答固定文本；call（hook 摘要调用）返回固定摘要 */
    class RecordingModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return respond(FIXED_SUMMARY);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamedPrompts.add(new ArrayList<>(prompt.getInstructions()));
            return Flux.just(respond("好的"));
        }

        private ChatResponse respond(String text) {
            AssistantMessage msg = AssistantMessage.builder().content(text).properties(Map.of()).build();
            return new ChatResponse(List.of(new Generation(msg)));
        }
    }
}
