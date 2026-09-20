package com.javaagent.agent.facade;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.javaagent.agent.agent.AgentFactory;
import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.Turn;
import com.javaagent.agent.persistence.TurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.when;

class AgentFacadeTest {

    private AgentFacade facade;
    private InMemoryConversationRepository conversations;
    private InMemoryTurnRepository turns;
    private InMemoryMessageRepository messages;
    /** 桩 agent 每次订阅前向 side sink 预置的事件（模拟 ThinkingTap / 工具拦截器发射） */
    private final List<Object> stubSideEvents = new ArrayList<>();
    /** 桩 agent 主流：null = 默认两个文本 chunk；否则按此 Flux 输出 */
    private Flux<com.alibaba.cloud.ai.graph.NodeOutput> stubMainFlux;
    private Sinks.Many<Object> capturedSink;

    @BeforeEach
    void setUp() {
        conversations = new InMemoryConversationRepository();
        turns = new InMemoryTurnRepository();
        messages = new InMemoryMessageRepository();
        stubSideEvents.clear();
        stubMainFlux = null;

        AgentFactory factory = mock(AgentFactory.class);
        when(factory.create(any(), any(), any())).thenAnswer(inv -> {
            capturedSink = inv.getArgument(0);
            ReactAgent agent = mock(ReactAgent.class);
            when(agent.stream(any(UserMessage.class), any(RunnableConfig.class)))
                .thenAnswer(streamInv -> {
                    // 订阅前预置 side 事件（真实链路中由 ThinkingTap/拦截器在主流执行期间发射）
                    stubSideEvents.forEach(evt -> capturedSink.tryEmitNext(evt));
                    if (stubMainFlux != null) {
                        return stubMainFlux;
                    }
                    return Flux.just(stubStreamingOutput("回"), stubStreamingOutput("答"));
                });
            return new AgentFactory.AgentHandle(agent, null, "stub-system-prompt");
        });

        facade = new AgentFacade(factory, conversations, turns, messages, "step-3.7-flash");
    }

    @Test
    void chatEmitsMetaDeltaTurnDoneAndPersistsCompleteMessages() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, Instant.now(), Instant.now()));

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

        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "TEXT");
        assertThat(saved.get(1).content()).isEqualTo("回答");
    }

    @Test
    void chatPersistsUserMessageWithSeqZero() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, Instant.now(), Instant.now()));
        facade.chat(conv.id(), "问题").blockLast();

        assertThat(messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id()).get(0).seq()).isEqualTo(0);
    }

    @Test
    void thinkingAndToolEventsFromSideSinkAreMergedAndPersisted() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, Instant.now(), Instant.now()));
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
            new Conversation(null, "t", "conv-1", null, Instant.now(), Instant.now()));
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

    /** 桩：LLM 节点流式 chunk（SAA 实测 API：StreamingOutput(Message, node, agent, state)） */
    private com.alibaba.cloud.ai.graph.NodeOutput stubStreamingOutput(String chunk) {
        return new StreamingOutput<>(new AssistantMessage(chunk), "llm", "java-agent", new OverAllState());
    }

    // ---- InMemory 仓储（覆盖接口全部方法，语义与 JPA/SDA 一致） ----

    static class InMemoryConversationRepository implements ConversationRepository {
        long nextId = 1;
        final List<Conversation> data = new ArrayList<>();
        @SuppressWarnings("unchecked")
        @Override public <S extends Conversation> S save(S e) {
            Long id = e.id() == null ? nextId++ : e.id();
            Conversation saved = new Conversation(id, e.title(), e.threadId(), e.compactSummary(), e.createdAt(), e.updatedAt());
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
        @Override public List<Conversation> findAllByOrderByUpdatedAtDesc() { return List.copyOf(data); }
        @Override public void touch(Long id) {
            findById(id).ifPresent(c ->
                data.set(data.indexOf(c), new Conversation(c.id(), c.title(), c.threadId(),
                    c.compactSummary(), c.createdAt(), Instant.now())));
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
