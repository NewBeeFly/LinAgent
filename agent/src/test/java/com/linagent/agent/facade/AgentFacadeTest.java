package com.linagent.agent.facade;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.linagent.agent.agent.AgentFactory;
import com.linagent.agent.compaction.CompactionService;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.persistence.Conversation;
import com.linagent.agent.persistence.ConversationRepository;
import com.linagent.agent.persistence.Message;
import com.linagent.agent.persistence.MessageRepository;
import com.linagent.agent.persistence.Turn;
import com.linagent.agent.persistence.TurnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFacadeTest {

    private AgentFacade facade;
    private InMemoryConversationRepository conversations;
    private InMemoryTurnRepository turns;
    private InMemoryMessageRepository messages;
    private CompactionService compaction;
    /** 桩 agent 每次订阅前向 side sink 预置的事件（模拟 ThinkingTap / 工具拦截器发射） */
    private final List<Object> stubSideEvents = new ArrayList<>();
    /** 桩 agent 主流：null = 默认两个文本 chunk；否则按此 Flux 输出 */
    private Flux<com.alibaba.cloud.ai.graph.NodeOutput> stubMainFlux;
    /** 桩 usage 捕获值（模拟 ThinkingTapChatModel 在流上捕获 Spring AI Usage）；null = 不捕获 */
    private Object stubUsage;
    private Sinks.Many<Object> capturedSink;
    private ReactAgent capturedAgent;
    private AtomicReference<Object> capturedUsageRef;

    @BeforeEach
    void setUp() {
        conversations = new InMemoryConversationRepository();
        turns = new InMemoryTurnRepository();
        messages = new InMemoryMessageRepository();
        compaction = mock(CompactionService.class);
        when(compaction.compactIfNeeded(any())).thenReturn(Optional.empty());
        stubSideEvents.clear();
        stubMainFlux = null;
        // 默认喂一份真实捕获形态的 usage（终审 Important 2：收尾落 turn.usage）
        stubUsage = usage(10, 20, 30);

        AgentFactory factory = mock(AgentFactory.class);
        when(factory.create(any(), any(), any(), any())).thenAnswer(inv -> {
            capturedSink = inv.getArgument(1);
            capturedUsageRef = inv.getArgument(2);
            ReactAgent agent = mock(ReactAgent.class);
            when(agent.stream(any(UserMessage.class), any(RunnableConfig.class)))
                .thenAnswer(streamInv -> stubAgentMainFlux());
            // 摘要前置路径：List 输入重载（SystemMessage + UserMessage）
            when(agent.stream(any(List.class), any(RunnableConfig.class)))
                .thenAnswer(streamInv -> stubAgentMainFlux());
            capturedAgent = agent;
            return new AgentFactory.AgentHandle(agent, null, "stub-system-prompt");
        });

        facade = new AgentFacade(factory, compaction, conversations, turns, messages, "step-3.7-flash");

        // ThreadLocal 边界运输：facade.chat() 在 defer 外 require()——测试线程即调用线程，
        // 须先 set；本测试全部会话桩归属 ("default","linmj")，与 InMemory double 的
        // findByIdAndTenantIdAndUserId 真实过滤匹配（归属打桩由此成立）
        AuthContextHolder.set(new AuthContext("default", "linmj"));
    }

    @AfterEach
    void cleanAuthContext() {
        AuthContextHolder.clear();
    }

    /** Spring AI Usage 桩（ThinkingTapChatModel 捕获的真实载荷形态；getter 返回 Integer） */
    private static org.springframework.ai.chat.metadata.Usage usage(int prompt, int completion, int total) {
        org.springframework.ai.chat.metadata.Usage u = mock(org.springframework.ai.chat.metadata.Usage.class);
        when(u.getPromptTokens()).thenReturn(prompt);
        when(u.getCompletionTokens()).thenReturn(completion);
        when(u.getTotalTokens()).thenReturn(total);
        return u;
    }

    /** 桩 agent 主流统一行为：side 预置事件 + 主流输出 */
    private Flux<com.alibaba.cloud.ai.graph.NodeOutput> stubAgentMainFlux() {
        // 订阅前预置 side 事件（真实链路中由 ThinkingTap/拦截器在主流执行期间发射）
        stubSideEvents.forEach(evt -> capturedSink.tryEmitNext(evt));
        // 模拟 ThinkingTapChatModel：主流响应到达时把 usage 写入捕获器
        if (stubUsage != null) {
            capturedUsageRef.set(stubUsage);
        }
        if (stubMainFlux != null) {
            return stubMainFlux;
        }
        return Flux.just(stubStreamingOutput("回"), stubStreamingOutput("答"));
    }

    @Test
    void chatEmitsMetaDeltaTurnDoneAndPersistsCompleteMessages() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta m && m.model().equals("step-3.7-flash"))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("回"))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("答"))
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone
                && ((AgentEvent.TurnDone) e).finishReason().equals("STOP"))
            .verifyComplete();

        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("COMPLETED");
        // 终审 Important 2：收尾把 ThinkingTap 捕获的 usage 序列化落 turn.usage（JSONB）
        assertThat(turn.get().usage())
            .contains("\"promptTokens\":10")
            .contains("\"completionTokens\":20")
            .contains("\"totalTokens\":30");

        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "TEXT");
        assertThat(saved.get(1).content()).isEqualTo("回答");
    }

    /** 终审 Important 2 专项：usage 捕获缺失（ThinkingTap 未捕获到）时归零落库，不抛异常 */
    @Test
    void chatWithoutCapturedUsageFallsBackToZeroUsageJson() {
        stubUsage = null;
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone
                && ((AgentEvent.TurnDone) e).usage().totalTokens() == 0)
            .verifyComplete();

        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("COMPLETED");
        assertThat(turn.get().usage()).isEqualTo(
            "{\"promptTokens\":0,\"completionTokens\":0,\"totalTokens\":0}");
    }

    @Test
    void chatPersistsUserMessageWithSeqZero() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        facade.chat(conv.id(), "问题").blockLast();

        assertThat(messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id()).get(0).seq()).isEqualTo(0);
    }

    @Test
    void thinkingAndToolEventsFromSideSinkAreMergedAndPersisted() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        // side 流两种载荷形态：String（thinking 增量，来自 ThinkingTap）与
        // 已构造好的 AgentEvent.ToolCall（来自 EventEmittingToolInterceptor）
        stubSideEvents.add("思");
        stubSideEvents.add("考");
        stubSideEvents.add(new AgentEvent.ToolCall(999L, "call-1", "list_dir", "{}"));

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.ThinkingDelta d && d.content().equals("思"))
            .expectNextMatches(e -> e instanceof AgentEvent.ThinkingDelta d && d.content().equals("考"))
            .expectNextMatches(e -> e instanceof AgentEvent.ToolCall tc
                && tc.callId().equals("call-1")
                // facade 对已构造事件原样透传（turnId 由拦截器负责填入）
                && tc.turnId().equals(999L))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("回"))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("答"))
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone)
            .verifyComplete();

        // thinking 段落经 flushAll 落库；工具消息的落库由 EventEmittingToolInterceptor
        // 直接写 buffer（不走 sink），facade 对已构造的 ToolCall 事件仅透传
        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "THINKING", "TEXT");
        assertThat(saved.get(1).content()).isEqualTo("思考");
    }

    @Test
    void chatErrorEmitsTurnErrorMarksTurnFailedAndKeepsStreamComplete() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        stubMainFlux = Flux.error(new RuntimeException("模型连接失败"));

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.TurnError te
                && te.code().equals("AGENT_ERROR") && te.message().equals("模型连接失败"))
            .verifyComplete();

        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("FAILED");

        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "ERROR");
    }

    @Test
    void chatCancelMarksTurnFailedAndPersistsCompletedSegments() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        // 主流发出首个 chunk 后挂起：订阅方在收到部分内容后取消（SSE 断连场景）
        stubMainFlux = Flux.just(stubStreamingOutput("回"))
            .concatWith(Flux.never());

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("回"))
            .thenCancel()
            .verify();

        // CANCEL 路径：turn 终态 FAILED/CANCELLED（不再永久 RUNNING），已完成片段落库
        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("FAILED");
        assertThat(turn.get().finishReason()).isEqualTo("CANCELLED");

        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "TEXT");
        assertThat(saved.get(1).content()).isEqualTo("回");
    }

    /**
     * fix round 1：TURN 收尾（TurnDone 已 finalize、turn COMPLETED）之后才到达的 CANCEL
     * 不得把 COMPLETED 覆写为 FAILED——once-guard 保证终态只落一次。
     */
    @Test
    void chatCancelAfterTurnDoneKeepsCompletedStatus() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("回"))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("答"))
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone)
            .thenCancel()
            .verify();

        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("COMPLETED");
        assertThat(turn.get().finishReason()).isEqualTo("STOP");

        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "TEXT");
    }

    @Test
    void chatRunsCompactionAndSwitchesToNewThreadWithSummaryPrefix() throws com.alibaba.cloud.ai.graph.exception.GraphRunnerException {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        // 模拟 CompactionService 的副作用：更新 conversation 的 threadId + compact_summary
        when(compaction.compactIfNeeded(conv.id())).thenAnswer(inv -> {
            conversations.save(new Conversation(conv.id(), conv.title(), "conv-1-v1",
                "压缩后的历史摘要", 2, conv.tenantId(), conv.userId(), conv.createdAt(), Instant.now()));
            return Optional.of("conv-1-v1");
        });

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone)
            .verifyComplete();

        // facade 重新读取 conversation：新 threadId 进 RunnableConfig
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> inputCaptor =
            ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        verify(capturedAgent).stream(inputCaptor.capture(), configCaptor.capture());
        assertThat(configCaptor.getValue().threadId()).contains("conv-1-v1");

        // 摘要以 SystemMessage 前置到发给 agent 的输入消息
        List<org.springframework.ai.chat.messages.Message> input = inputCaptor.getValue();
        assertThat(input).hasSize(2);
        assertThat(input.get(0)).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(input.get(0).getText()).isEqualTo("此前对话摘要：压缩后的历史摘要");
        assertThat(input.get(1)).isInstanceOf(UserMessage.class);
        assertThat(input.get(1).getText()).isEqualTo("你好");
    }

    @Test
    void chatWithExistingCompactSummaryAlwaysPrependsSummarySystemMessage() throws com.alibaba.cloud.ai.graph.exception.GraphRunnerException {
        // 已压缩会话（compact_summary 已存在、本轮未再触发压缩）：摘要常驻输入
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1-v1", "既有摘要", 2, "default", "linmj", Instant.now(), Instant.now()));

        StepVerifier.create(facade.chat(conv.id(), "继续"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone)
            .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> inputCaptor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(capturedAgent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        assertThat(inputCaptor.getValue().get(0).getText()).isEqualTo("此前对话摘要：既有摘要");
        assertThat(inputCaptor.getValue().get(1).getText()).isEqualTo("继续");
    }

    @Test
    void chatWithoutSummarySendsPlainUserMessage() throws com.alibaba.cloud.ai.graph.exception.GraphRunnerException {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        facade.chat(conv.id(), "你好").blockLast();

        ArgumentCaptor<UserMessage> inputCaptor = ArgumentCaptor.forClass(UserMessage.class);
        verify(capturedAgent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        assertThat(inputCaptor.getValue().getText()).isEqualTo("你好");
    }

    /** 桩：LLM 节点流式 chunk（SAA 实测 API：StreamingOutput(Message, node, agent, state)） */
    private com.alibaba.cloud.ai.graph.NodeOutput stubStreamingOutput(String chunk) {
        return new StreamingOutput<>(new AssistantMessage(chunk), "llm", "lin-agent", new OverAllState());
    }

    // ---- InMemory 仓储（覆盖接口全部方法，语义与 JPA/SDA 一致） ----

    static class InMemoryConversationRepository implements ConversationRepository {
        long nextId = 1;
        final List<Conversation> data = new ArrayList<>();
        @SuppressWarnings("unchecked")
        @Override public <S extends Conversation> S save(S e) {
            Long id = e.id() == null ? nextId++ : e.id();
            Conversation saved = new Conversation(id, e.title(), e.threadId(), e.compactSummary(),
                e.compactedTurnSeq() == null ? 0 : e.compactedTurnSeq(),
                e.tenantId(), e.userId(), e.createdAt(), e.updatedAt());
            data.removeIf(c -> c.id().equals(id));
            data.add(saved);
            return (S) saved;
        }
        @Override public <S extends Conversation> List<S> saveAll(Iterable<S> entities) {
            List<S> result = new ArrayList<>();
            entities.forEach(e -> result.add(save(e)));
            return result;
        }
        @Override public Optional<Conversation> findById(Long id) {
            return data.stream().filter(c -> c.id().equals(id)).findFirst();
        }
        @Override public boolean existsById(Long id) { return findById(id).isPresent(); }
        @Override public List<Conversation> findAll() { return List.copyOf(data); }
        @Override public List<Conversation> findAllById(Iterable<Long> ids) { return List.copyOf(data); }
        @Override public long count() { return data.size(); }
        @Override public void deleteById(Long id) { data.removeIf(c -> c.id().equals(id)); }
        @Override public void delete(Conversation entity) { deleteById(entity.id()); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(this::deleteById); }
        @Override public void deleteAll(Iterable<? extends Conversation> entities) { entities.forEach(this::delete); }
        @Override public void deleteAll() { data.clear(); }
        /** 多租户查询（Task 4 起真实按归属过滤，防跨任务静默串租户） */
        @Override public List<Conversation> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId) {
            return data.stream()
                .filter(c -> java.util.Objects.equals(c.tenantId(), tenantId)
                    && java.util.Objects.equals(c.userId(), userId))
                .toList();
        }
        @Override public Optional<Conversation> findByIdAndTenantIdAndUserId(Long id, String tenantId, String userId) {
            return data.stream()
                .filter(c -> c.id().equals(id)
                    && java.util.Objects.equals(c.tenantId(), tenantId)
                    && java.util.Objects.equals(c.userId(), userId))
                .findFirst();
        }
        @Override public void touch(Long id) {
            findById(id).ifPresent(c ->
                data.set(data.indexOf(c), new Conversation(c.id(), c.title(), c.threadId(),
                    c.compactSummary(), c.compactedTurnSeq(), c.tenantId(), c.userId(),
                    c.createdAt(), Instant.now())));
        }
    }

    static class InMemoryTurnRepository implements TurnRepository {
        long nextId = 1;
        final List<Turn> data = new ArrayList<>();
        @SuppressWarnings("unchecked")
        @Override public <S extends Turn> S save(S e) {
            Long id = e.id() == null ? nextId++ : e.id();
            Turn saved = new Turn(id, e.conversationId(), e.seq(), e.status(), e.finishReason(), e.usage(), e.startedAt(), e.finishedAt());
            data.removeIf(t -> t.id().equals(id));
            data.add(saved);
            return (S) saved;
        }
        @Override public <S extends Turn> List<S> saveAll(Iterable<S> entities) {
            List<S> result = new ArrayList<>();
            entities.forEach(e -> result.add(save(e)));
            return result;
        }
        @Override public Optional<Turn> findById(Long id) {
            return data.stream().filter(t -> t.id().equals(id)).findFirst();
        }
        @Override public boolean existsById(Long id) { return findById(id).isPresent(); }
        @Override public List<Turn> findAll() { return List.copyOf(data); }
        @Override public List<Turn> findAllById(Iterable<Long> ids) { return List.copyOf(data); }
        @Override public long count() { return data.size(); }
        @Override public void deleteById(Long id) { data.removeIf(t -> t.id().equals(id)); }
        @Override public void delete(Turn entity) { deleteById(entity.id()); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(this::deleteById); }
        @Override public void deleteAll(Iterable<? extends Turn> entities) { entities.forEach(this::delete); }
        @Override public void deleteAll() { data.clear(); }
        @Override public int countByConversationId(Long conversationId) {
            return (int) data.stream().filter(t -> t.conversationId().equals(conversationId)).count();
        }
        @Override public Optional<Turn> findTopByConversationIdOrderBySeqDesc(Long conversationId) {
            return data.stream().filter(t -> t.conversationId().equals(conversationId))
                .max(java.util.Comparator.comparing(Turn::seq));
        }
        @Override public List<Turn> findByConversationIdOrderBySeqAsc(Long conversationId) {
            return data.stream().filter(t -> t.conversationId().equals(conversationId))
                .sorted(java.util.Comparator.comparing(Turn::seq)).toList();
        }
    }

    static class InMemoryMessageRepository implements MessageRepository {
        long nextId = 1;
        final List<Message> data = new ArrayList<>();
        @SuppressWarnings("unchecked")
        @Override public <S extends Message> S save(S e) {
            Long id = e.id() == null ? nextId++ : e.id();
            Message saved = new Message(id, e.turnId(), e.seq(), e.msgType(), e.content(), e.callId(),
                e.toolName(), e.arguments(), e.result(), e.success(), e.durationMs(), e.createdAt());
            data.removeIf(m -> m.id().equals(id));
            data.add(saved);
            return (S) saved;
        }
        @Override public <S extends Message> List<S> saveAll(Iterable<S> entities) {
            List<S> result = new ArrayList<>();
            entities.forEach(e -> result.add(save(e)));
            return result;
        }
        @Override public Optional<Message> findById(Long id) {
            return data.stream().filter(m -> m.id().equals(id)).findFirst();
        }
        @Override public boolean existsById(Long id) { return findById(id).isPresent(); }
        @Override public List<Message> findAll() { return List.copyOf(data); }
        @Override public List<Message> findAllById(Iterable<Long> ids) { return List.copyOf(data); }
        @Override public long count() { return data.size(); }
        @Override public void deleteById(Long id) { data.removeIf(m -> m.id().equals(id)); }
        @Override public void delete(Message entity) { deleteById(entity.id()); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(this::deleteById); }
        @Override public void deleteAll(Iterable<? extends Message> entities) { entities.forEach(this::delete); }
        @Override public void deleteAll() { data.clear(); }
        @Override public List<Message> findByTurnIdOrderBySeq(Long turnId) {
            return data.stream().filter(m -> turnId.equals(m.turnId()))
                .sorted(java.util.Comparator.comparing(Message::seq)).toList();
        }
        @Override public List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId) {
            // 测试内会话只含单 turn，按 turnId/seq 全序返回语义一致
            return data.stream()
                .sorted(java.util.Comparator.comparing(Message::turnId)
                    .thenComparing(Message::seq))
                .toList();
        }
    }
}
