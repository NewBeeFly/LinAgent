package com.linagent.agent.facade;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.linagent.agent.agent.AgentFactory;
import com.linagent.agent.approval.ApprovalHook;
import com.linagent.agent.approval.PermissionRuleEngine;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.conversation.ChatMode;
import com.linagent.agent.persistence.po.Conversation;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.po.Turn;
import com.linagent.agent.persistence.repository.TurnRepository;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFacadeTest {

    private AgentFacade facade;
    private AgentFactory factory;
    private InMemoryConversationRepository conversations;
    private InMemoryTurnRepository turns;
    private InMemoryMessageRepository messages;
    /** 桩 agent 每次订阅前向 side sink 预置的事件（模拟 ThinkingTap / 工具拦截器发射） */
    private final List<Object> stubSideEvents = new ArrayList<>();
    /** 桩 agent 主流：null = 默认两个文本 chunk；否则按此 Flux 输出 */
    private Flux<com.alibaba.cloud.ai.graph.NodeOutput> stubMainFlux;
    /** 桩 usage 捕获值（模拟 ThinkingTapChatModel 在流上捕获 Spring AI Usage）；null = 不捕获 */
    private Object stubUsage;
    private Sinks.Many<Object> capturedSink;
    private ReactAgent capturedAgent;
    private AtomicReference<Object> capturedUsageRef;
    /** facade 传给 factory 的会话档位（conv.mode 经 ChatMode.parse 后的值） */
    private ChatMode capturedMode;

    @BeforeEach
    void setUp() {
        conversations = new InMemoryConversationRepository();
        turns = new InMemoryTurnRepository();
        messages = new InMemoryMessageRepository();
        stubSideEvents.clear();
        stubMainFlux = null;
        capturedMode = null;
        // 默认喂一份真实捕获形态的 usage（终审 Important 2：收尾落 turn.usage）
        stubUsage = usage(10, 20, 30);

        factory = mock(AgentFactory.class);
        when(factory.create(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            // create(ctx, conversationId, thinkingSink, usageCapture, toolInterceptor, mode)
            capturedSink = inv.getArgument(2);
            capturedUsageRef = inv.getArgument(3);
            capturedMode = inv.getArgument(5);
            ReactAgent agent = mock(ReactAgent.class);
            when(agent.stream(any(UserMessage.class), any(RunnableConfig.class)))
                .thenAnswer(streamInv -> stubAgentMainFlux());
            // resume 路径（Task 6，spike 结论 B）：stream(Map.of(), config)
            when(agent.stream(org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any(),
                any(RunnableConfig.class)))
                .thenAnswer(streamInv -> stubAgentMainFlux());
            capturedAgent = agent;
            return new AgentFactory.AgentHandle(agent, null, "stub-system-prompt", config -> { }, capturedMode);
        });

        facade = new AgentFacade(factory, conversations, turns, messages, "step-3.7-flash");

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
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "STANDARD", Instant.now(), Instant.now()));

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
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "STANDARD", Instant.now(), Instant.now()));

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
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "STANDARD", Instant.now(), Instant.now()));
        facade.chat(conv.id(), "问题").blockLast();

        assertThat(messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id()).get(0).seq()).isEqualTo(0);
    }

    // ---- v0.3 会话模式：facade → factory 传参 ----

    /** chat 把 conv.mode（经 ChatMode.parse）传给 AgentFactory 六参 create */
    @Test
    void chatPassesConversationModeToFactory() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "CHAT", Instant.now(), Instant.now()));

        facade.chat(conv.id(), "你好").blockLast();

        assertThat(capturedMode).isEqualTo(ChatMode.CHAT);
    }

    /** mode 列空值（V8 迁移前的存量语义/异常数据）：parse 回落 STANDARD 传入 */
    @Test
    void chatFallsBackToStandardWhenModeColumnBlank() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", null, Instant.now(), Instant.now()));

        facade.chat(conv.id(), "你好").blockLast();

        assertThat(capturedMode).isEqualTo(ChatMode.STANDARD);
    }

    @Test
    void thinkingAndToolEventsFromSideSinkAreMergedAndPersisted() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "STANDARD", Instant.now(), Instant.now()));
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
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "STANDARD", Instant.now(), Instant.now()));
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
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "STANDARD", Instant.now(), Instant.now()));
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
            new Conversation(null, "t", "conv-1", null, 0, "default", "linmj", "STANDARD", Instant.now(), Instant.now()));

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
    void chatAlwaysSendsPlainUserMessageRegardlessOfLegacySummary() throws com.alibaba.cloud.ai.graph.exception.GraphRunnerException {
        Conversation conv = conversations.seed("遗留摘要正文");
        facade.chat(conv.id(), "你好").blockLast();
        // hook 化后 AgentFacade 恒只传当前 UserMessage（历史由 checkpoint 恢复；
        // 旧 compact_summary 列不再参与输入——根治重复注入）
        verify(capturedAgent).stream(any(UserMessage.class), any(RunnableConfig.class));
        verify(capturedAgent, never()).stream(any(List.class), any(RunnableConfig.class));
    }

    // ---- Task 5：审批中断路径 ----

    /** 待审批轮未决时 chat 同步抛 ApprovalPendingException（web 层 Task 6 映射 409），
     *  先于建流/建 turn——避免同一会话两个未决审批的 checkpoint 竞态 */
    @Test
    void chatWhenApprovalPendingThrowsBeforeStreaming() {
        Conversation conv = conversations.seed(null);
        turns.save(new Turn(null, conv.id(), 1, "WAITING_APPROVAL", null, null, Instant.now(), null));

        assertThatThrownBy(() -> facade.chat(conv.id(), "再问一句"))
            .isInstanceOf(ApprovalPendingException.class);

        // 抛出先于 agent 构建：桩 agent 从未被创建
        assertThat(capturedAgent).isNull();
    }

    /** 中断路径（spike 结论 A：流尾元素为 InterruptionMetadata，流正常 complete）：
     *  发 ApprovalRequest（items 完整、turnId/conversationId 齐）、跳过 TurnDone、
     *  turn 落 WAITING_APPROVAL（finishedAt=null）、pending 项 TOOL_CALL 行先于事件落库 */
    @Test
    void interruptedTurnEmitsApprovalRequestSkipsTurnDoneAndPersistsPendingToolCalls() {
        Conversation conv = conversations.seed(null);
        stubMainFlux = Flux.just(stubStreamingOutput("回"),
            stubApprovalInterruption(pendingShellItem()));

        StepVerifier.create(facade.chat(conv.id(), "删文件"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("回"))
            .expectNextMatches(e -> e instanceof AgentEvent.ApprovalRequest ar
                && ar.conversationId().equals(conv.id())
                && ar.items().size() == 1
                && ar.items().get(0).callId().equals("call-9")
                && ar.items().get(0).toolName().equals("shell")
                && ar.items().get(0).arguments().equals("{\"command\":\"rm -rf /tmp/x\"}"))
            .verifyComplete(); // 无 TurnDone：中断路径以 ApprovalRequest 收尾

        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("WAITING_APPROVAL");
        assertThat(turn.get().finishedAt()).isNull();

        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "TEXT", "TOOL_CALL");
        Message toolCall = saved.get(2);
        assertThat(toolCall.callId()).isEqualTo("call-9");
        assertThat(toolCall.toolName()).isEqualTo("shell");
        // arguments 落模型原始 JSON（审批卡片回放数据源），非引擎提取后的 payload
        assertThat(toolCall.arguments()).isEqualTo("{\"command\":\"rm -rf /tmp/x\"}");
    }

    /** Cancel 交互：中断已落 WAITING_APPROVAL 后订阅方断连 → 转 FAILED
     *  （CANCELLED_WHILE_WAITING），不遗留等待轮锁死 409 前置检查 */
    @Test
    void chatCancelWhileWaitingApprovalMarksTurnFailed() {
        Conversation conv = conversations.seed(null);
        // 中断为流尾元素，但其后挂 never 制造 CANCEL 窗口（SSE 断连场景）
        stubMainFlux = Flux.just(stubStreamingOutput("回"), stubApprovalInterruption(pendingShellItem()))
            .concatWith(Flux.never());

        StepVerifier.create(facade.chat(conv.id(), "删文件"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.ApprovalRequest)
            .thenCancel()
            .verify();

        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("FAILED");
        assertThat(turn.get().finishReason()).isEqualTo("CANCELLED_WHILE_WAITING");
    }

    /** 非 ApprovalHook 产生的中断（metadata 无待审批项）：按普通输出丢弃，
     *  正常收 TurnDone——防御性回退，不因未知中断形态吞掉正常收尾 */
    @Test
    void nonApprovalInterruptionFallsThroughToTurnDone() {
        Conversation conv = conversations.seed(null);
        stubMainFlux = Flux.just(stubStreamingOutput("回"),
            InterruptionMetadata.builder("_AGENT_HOOK_HITL", new OverAllState()).build());

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta)
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone)
            .verifyComplete();

        assertThat(turns.findTopByConversationIdOrderBySeqDesc(conv.id()).orElseThrow().status())
            .isEqualTo("COMPLETED");
    }

    // ---- Task 6：resume 决议续跑 ----

    /** 无等待轮时 resume 同步抛 ApprovalConflictException（重复提交竞态兜底），先于建流 */
    @Test
    void resumeWithoutWaitingTurnThrowsConflict() {
        Conversation conv = conversations.seed(null);
        // 无 WAITING_APPROVAL 轮（会话空 / 已决议）

        assertThatThrownBy(() -> facade.resume(conv.id(), approveAllFeedback()))
            .isInstanceOf(ApprovalConflictException.class);

        assertThat(capturedAgent).isNull(); // 抛出先于 agent 构建
    }

    /**
     * 终审 I1：并发双决议占轮裁决——两个并发 POST 同时通过 pending 预检时，
     * 第一个 resume 原子占轮（claim 返回 1，turn 即刻 WAITING→RUNNING，不依赖订阅），
     * 第二个 resume 定位等待轮已落空 → 同步 ApprovalConflictException（web 映射 409），
     * 不再从同一 checkpoint 双恢复（已批准工具会执行两次）。
     */
    @Test
    void resumeLosingConcurrentClaimThrowsConflictBeforeStreaming() {
        Conversation conv = conversations.seed(null);
        turns.save(new Turn(null, conv.id(), 1, "WAITING_APPROVAL", null, null, Instant.now(), null));

        // 赢者：占轮成功（同步段即生效，订阅与否不影响门闸语义）
        facade.resume(conv.id(), approveAllFeedback());
        assertThat(turns.data.get(0).status()).isEqualTo("RUNNING");

        // 输者：同一等待轮已被占走 → 409 语义异常，且不触碰 agent
        assertThatThrownBy(() -> facade.resume(conv.id(), approveAllFeedback()))
            .isInstanceOf(ApprovalConflictException.class);
        assertThat(capturedAgent).isNull();
    }

    /** 紧并发窗口（终审 I1）：两个 findTop 都读到 WAITING（赢者 claim 尚未提交）时，
     *  输者的 claim 原子落空（返回 0）→ 独立的 409 语义分支 */
    @Test
    void resumeClaimReturningZeroThrowsConflict() {
        Conversation conv = conversations.seed(null);
        Turn waiting = turns.save(
            new Turn(null, conv.id(), 1, "WAITING_APPROVAL", null, null, Instant.now(), null));

        TurnRepository racingTurns = mock(TurnRepository.class);
        when(racingTurns.findTopByConversationIdAndStatusOrderBySeqDesc(conv.id(), "WAITING_APPROVAL"))
            .thenReturn(Optional.of(waiting));
        when(racingTurns.claimWaitingTurn(waiting.id())).thenReturn(0);
        AgentFacade racingFacade = new AgentFacade(factory, conversations, racingTurns, messages, "step-3.7-flash");

        assertThatThrownBy(() -> racingFacade.resume(conv.id(), approveAllFeedback()))
            .isInstanceOf(ApprovalConflictException.class)
            .hasMessageContaining("并发决议抢占");
        assertThat(capturedAgent).isNull();
    }

    /** resume 复用等待轮：WAITING_APPROVAL → RUNNING → COMPLETED，不新建 turn/USER 行，
     *  展示层 seq 从既有最大 seq 续接，图输入为 Map.of()（spike 结论 B，绝不传 UserMessage） */
    @Test
    void resumeCompletesWaitingTurnWithoutNewUserRowAndContinuesSeq()
            throws com.alibaba.cloud.ai.graph.exception.GraphRunnerException {
        Conversation conv = conversations.seed(null);
        // 先跑一轮到中断：USER(0) + TEXT(1) + TOOL_CALL(2)
        stubMainFlux = Flux.just(stubStreamingOutput("回"),
            stubApprovalInterruption(pendingShellItem()));
        facade.chat(conv.id(), "删文件").blockLast();
        Long turnId = turns.findTopByConversationIdOrderBySeqDesc(conv.id()).orElseThrow().id();
        assertThat(turns.findById(turnId).orElseThrow().status()).isEqualTo("WAITING_APPROVAL");

        // 决议续跑：两段正文 → TurnDone
        stubMainFlux = Flux.just(stubStreamingOutput("执"), stubStreamingOutput("行"));
        StepVerifier.create(facade.resume(conv.id(), approveAllFeedback()))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta m && m.turnId().equals(turnId))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("执"))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("行"))
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone
                && ((AgentEvent.TurnDone) e).finishReason().equals("STOP"))
            .verifyComplete();

        // 图输入形态钉死：resume 走 Map 空 inputs，不发 UserMessage（防幻影历史回归）
        verify(capturedAgent).stream(any(java.util.Map.class), any(RunnableConfig.class));

        // turn 终态 COMPLETED；轮次总数不变（复用等待轮）；USER 行仍只 1 条
        assertThat(turns.countByConversationId(conv.id())).isEqualTo(1);
        assertThat(turns.findById(turnId).orElseThrow().status()).isEqualTo("COMPLETED");
        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType)
            .containsExactly("USER", "TEXT", "TOOL_CALL", "TEXT");
        // seq 续接：中断前最大 seq=2，续跑 TEXT 从 3 起（无 UNIQUE(turn_id, seq) 冲突）
        assertThat(saved.get(3).seq()).isEqualTo(3);
    }

    /** resume 续跑再次中断（模型又发起未放行调用）：同一管线递归生效——再发 ApprovalRequest、
     *  turn 再落 WAITING_APPROVAL、无 TurnDone（spec §6 递归语义） */
    @Test
    void resumeRecursiveInterruptionEmitsAnotherApprovalRequest() {
        Conversation conv = conversations.seed(null);
        stubMainFlux = Flux.just(stubApprovalInterruption(pendingShellItem()));
        facade.chat(conv.id(), "删文件").blockLast();
        Long turnId = turns.findTopByConversationIdOrderBySeqDesc(conv.id()).orElseThrow().id();

        stubMainFlux = Flux.just(stubStreamingOutput("再"),
            stubApprovalInterruption(new PermissionRuleEngine.PendingItem("call-10", "shell",
                "{\"command\":\"rm -rf /data\"}", "rm -rf /data",
                List.of(new PermissionRuleEngine.SubVerdict("rm -rf /data", false, null)), "rm *")));
        StepVerifier.create(facade.resume(conv.id(), approveAllFeedback()))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta)
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("再"))
            .expectNextMatches(e -> e instanceof AgentEvent.ApprovalRequest ar
                && ar.turnId().equals(turnId)
                && ar.items().get(0).callId().equals("call-10"))
            .verifyComplete(); // 无 TurnDone

        assertThat(turns.findById(turnId).orElseThrow().status()).isEqualTo("WAITING_APPROVAL");
        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType)
            .containsExactly("USER", "TOOL_CALL", "TEXT", "TOOL_CALL");
        assertThat(saved.get(3).callId()).isEqualTo("call-10");
    }

    /** resume 决议元数据桩：APPROVED + 全部待审批项（Task 6 端点构建形态） */
    private static InterruptionMetadata approveAllFeedback() {
        return InterruptionMetadata.builder(ApprovalHook.HITL_NODE_FULL_NAME, null)
            .addToolFeedback(InterruptionMetadata.ToolFeedback.builder()
                .id("call-9").name("shell").arguments("{\"command\":\"rm -rf /tmp/x\"}")
                .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED).build())
            .build();
    }

    /** 桩：一个待审批 shell 项（引擎 NEEDS_APPROVAL 形态，arguments 为模型原始 JSON） */
    private static PermissionRuleEngine.PendingItem pendingShellItem() {
        return new PermissionRuleEngine.PendingItem("call-9", "shell",
            "{\"command\":\"rm -rf /tmp/x\"}", "rm -rf /tmp/x",
            List.of(new PermissionRuleEngine.SubVerdict("rm -rf /tmp/x", false, null)), "rm *");
    }

    /**
     * 桩：审批中断形态输出（InterruptionMetadata 为 final 不可 mock，经 builder 构造真实实例）。
     * 与 ApprovalHook.buildInterruptionMetadata 同构：node=_AGENT_HOOK_HITL、待审批项经
     * {@link ApprovalHook#PENDING_ITEMS_METADATA_KEY} 挂 metadata、逐项 ToolFeedback。
     */
    private com.alibaba.cloud.ai.graph.NodeOutput stubApprovalInterruption(
            PermissionRuleEngine.PendingItem... items) {
        InterruptionMetadata.Builder builder = InterruptionMetadata
            .builder("_AGENT_HOOK_HITL", new OverAllState())
            .addMetadata(ApprovalHook.PENDING_ITEMS_METADATA_KEY, List.of(items));
        for (PermissionRuleEngine.PendingItem item : items) {
            builder.addToolFeedback(InterruptionMetadata.ToolFeedback.builder()
                .id(item.callId()).name(item.toolName()).arguments(item.arguments()).build());
        }
        return builder.build();
    }

    @Test
    void displayStorageOnlyAppendsNeverRemoves() {
        Conversation conv = conversations.seed(null);
        List<Integer> sizes = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            facade.chat(conv.id(), "第" + i + "轮").blockLast();
            sizes.add(messages.data.size()); // InMemory double 的内部集合
        }
        // 单调递增且每轮都有新增：压缩只发生在 checkpoint 层（hook），turn/message 永不回删
        assertThat(sizes).isSorted();
        assertThat(sizes.get(sizes.size() - 1)).isGreaterThan(sizes.get(0));
    }

    /** 桩：LLM 节点流式 chunk（SAA 实测 API：StreamingOutput(Message, node, agent, state)） */
    private com.alibaba.cloud.ai.graph.NodeOutput stubStreamingOutput(String chunk) {
        return new StreamingOutput<>(new AssistantMessage(chunk), "llm", "lin-agent", new OverAllState());
    }

    // ---- InMemory 仓储（覆盖接口全部方法，语义与 JPA/SDA 一致） ----

    static class InMemoryConversationRepository implements ConversationRepository {
        long nextId = 1;
        final List<Conversation> data = new ArrayList<>();

        /** 测试辅助：落一条默认归属 ("default","linmj") 的会话（与 AuthContext 桩匹配），摘要列可空 */
        Conversation seed(String compactSummary) {
            long id = nextId;
            return save(new Conversation(null, "t", "conv-" + id, compactSummary, 0,
                "default", "linmj", "STANDARD", Instant.now(), Instant.now()));
        }

        @SuppressWarnings("unchecked")
        @Override public <S extends Conversation> S save(S e) {
            Long id = e.id() == null ? nextId++ : e.id();
            Conversation saved = new Conversation(id, e.title(), e.threadId(), e.compactSummary(),
                e.compactedTurnSeq() == null ? 0 : e.compactedTurnSeq(),
                e.tenantId(), e.userId(), e.mode(), e.createdAt(), e.updatedAt());
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
                    c.mode(), c.createdAt(), Instant.now())));
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
        @Override public boolean existsByConversationIdAndStatus(Long conversationId, String status) {
            return data.stream().anyMatch(t -> t.conversationId().equals(conversationId)
                && status.equals(t.status()));
        }
        @Override public Optional<Turn> findTopByConversationIdAndStatusOrderBySeqDesc(
                Long conversationId, String status) {
            return data.stream().filter(t -> t.conversationId().equals(conversationId)
                    && status.equals(t.status()))
                .max(java.util.Comparator.comparing(Turn::seq));
        }
        /** CAS 语义与 SQL 版一致：仅当 status=WAITING_APPROVAL 时置 RUNNING，返回受影响行数 */
        @Override public int claimWaitingTurn(Long id) {
            for (int i = 0; i < data.size(); i++) {
                Turn t = data.get(i);
                if (t.id().equals(id) && "WAITING_APPROVAL".equals(t.status())) {
                    data.set(i, new Turn(t.id(), t.conversationId(), t.seq(), "RUNNING",
                        t.finishReason(), t.usage(), t.startedAt(), t.finishedAt()));
                    return 1;
                }
            }
            return 0;
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
