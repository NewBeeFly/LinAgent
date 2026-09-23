package com.linagent.agent.facade;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.linagent.agent.agent.AgentFactory;
import com.linagent.agent.agent.EventEmittingToolInterceptor;
import com.linagent.agent.approval.ApprovalHook;
import com.linagent.agent.approval.PermissionRuleEngine;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.persistence.po.Conversation;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.po.Turn;
import com.linagent.agent.persistence.repository.TurnRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * agent 引擎唯一门面：chat(conversationId, content) → Flux&lt;AgentEvent&gt;。
 * 落库在 agent 模块内完成（web 层零持久化职责）。
 *
 * 事件三路合并：
 * 1. side（Sinks.Many）—— ThinkingTap 发 thinking 增量（String），EventEmittingToolInterceptor
 *    发已构造的 ToolCall/ToolResult 事件对象，本类 map 时区分两种载荷；
 * 2. main（agent.stream 的 Flux&lt;NodeOutput&gt;）—— LLM 节点流式 chunk → MessageDelta；
 * 3. 收尾 —— main 流 concatWith 发 TurnDone（含 usage、turn COMPLETED、会话 touch）。
 *
 * 审批中断（Task 5，spike 结论 A）：main 流尾元素为 InterruptionMetadata 时走中断分支——
 * 发 ApprovalRequest 流尾事件、turn 停在 WAITING_APPROVAL、跳过 TurnDone；会话存在
 * 未决等待轮时 chat 前置抛 ApprovalPendingException（先决议后继续）。
 *
 * side sink 的 complete 由 main 流终止时触发（doFinally），外层 doFinally 仅兜底，
 * 避免 merge 因 side 永不终止而悬挂。
 */
@Service
public class AgentFacade {

    private final AgentFactory agentFactory;
    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final String model;

    public AgentFacade(AgentFactory agentFactory,
                       ConversationRepository conversations, TurnRepository turns,
                       MessageRepository messages,
                       @Value("${spring.ai.openai.chat.options.model:step-3.7-flash}") String model) {
        this.agentFactory = agentFactory;
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.model = model;
    }

    public Flux<AgentEvent> chat(Long conversationId, String content) {
        // ThreadLocal 红线（spec §5）：defer 外捕获——订阅期在 reactor 线程执行，
        // 届时 Filter finally 已清理且线程不同，defer 内读 Holder 必炸。
        AuthContext ctx = AuthContextHolder.require();
        // 归属预检同步抛出：HTTP 404（GlobalExceptionHandler 映射）先于 SSE 建流
        conversations.requireOwned(conversationId, ctx.tenantId(), ctx.userId());
        // 审批未决前置（Task 5，web 层 Task 6 映射 409）：存在 WAITING_APPROVAL 轮即拒绝
        // 新消息——先决议后继续，避免同会话两个未决审批的 checkpoint 竞态
        // （resume 依赖 threadId 最新 checkpoint 的 nextNodeId）
        if (turns.existsByConversationIdAndStatus(conversationId, "WAITING_APPROVAL")) {
            throw new ApprovalPendingException(conversationId);
        }
        return Flux.defer(() -> {
            // 历史由 checkpoint 恢复（AppendStrategy 合并当前输入）；压缩在
            // SummarizingModelHook（BEFORE_MODEL）按真实消息触发，facade 不再预压缩
            Conversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));

            int turnSeq = turns.countByConversationId(conv.id()) + 1;
            Turn turn = turns.save(Turn.running(conv.id(), turnSeq));
            messages.save(Message.user(turn.id(), 0, content));

            SegmentBuffer buffer = new SegmentBuffer(turn.id(), messages);
            // 终态收口 once-guard（fix round 1）：flushAll→终态落库在 完成/异常/取消/中断
            // 四条路径中只执行一次——CANCEL 与末条 TurnDone 发射竞态时不会把 COMPLETED 覆写为
            // FAILED；flushAll 的跨线程原子性由 SegmentBuffer 自身 synchronized 保证
            AtomicBoolean finalized = new AtomicBoolean(false);
            // 审批中断标记（Task 5）：流尾 InterruptionMetadata 携带待审批项时置位，
            // TurnDone 收尾段据此跳过（流以 ApprovalRequest 收尾，turn 停在 WAITING_APPROVAL）
            AtomicBoolean interrupted = new AtomicBoolean(false);
            // 三路发射方（ThinkingTap / 工具拦截器，SAA 并行 tool call 时多线程）共享同一
            // unicast sink：经 SerializedEmitSink 串行收口，避免 FAIL_NON_SERIALIZED 静默丢事件
            Sinks.Many<Object> sideEvents =
                new SerializedEmitSink(Sinks.many().unicast().onBackpressureBuffer());
            AtomicReference<Object> usageCapture = new AtomicReference<>();

            EventEmittingToolInterceptor toolInterceptor =
                new EventEmittingToolInterceptor(sideEvents, buffer, turn.id());
            AgentFactory.AgentHandle handle =
                agentFactory.create(ctx, conversationId, sideEvents, usageCapture, toolInterceptor);

            RunnableConfig config = RunnableConfig.builder()
                .threadId(conv.threadId())
                .build();

            // side 流：String → ThinkingDelta（同时累积 buffer）；已构造的 AgentEvent 直接透传
            Flux<AgentEvent> side = sideEvents.asFlux().map(evt -> {
                if (evt instanceof String thinkingDelta) {
                    buffer.appendThinking(thinkingDelta);
                    return (AgentEvent) new AgentEvent.ThinkingDelta(turn.id(), thinkingDelta);
                }
                return (AgentEvent) evt;
            });

            // agent.stream 声明受检 GraphRunnerException，在 defer 内转 Flux.error 走统一错误路径；
            // 恒只传当前 UserMessage（spec §4：根治摘要重复注入）
            Flux<NodeOutput> nodeOutputs;
            try {
                nodeOutputs = handle.agent().stream(new UserMessage(content), config);
            } catch (GraphRunnerException e) {
                nodeOutputs = Flux.error(e);
            }

            Flux<AgentEvent> main = nodeOutputs
                .concatMap(nodeOutput -> {
                    // 审批中断检测（spike 结论 A：中断即流的最后元素为 InterruptionMetadata，
                    // 流随后正常 complete）：待审批项经 metadata 反解，走中断分支收尾
                    if (nodeOutput instanceof InterruptionMetadata md) {
                        return onApprovalInterruption(md, turn, conv, buffer, interrupted, finalized);
                    }
                    return mapNodeOutput(nodeOutput, turn.id(), buffer);
                })
                .concatWith(Flux.defer(() -> {
                    if (interrupted.get()) {
                        // 中断路径不收 TurnDone（流以 ApprovalRequest 收尾）；turn 终态
                        // 已由中断分支落 WAITING_APPROVAL，等待审批决议（Task 6 resume 补终态）
                        return Flux.empty();
                    }
                    // usage 在 complete 之前取值：ThinkingTap 捕获的最后一包 metadata
                    AgentEvent.Usage usage = toUsage(usageCapture.get());
                    if (finalized.compareAndSet(false, true)) {
                        buffer.flushAll();
                        turns.save(turns.findById(turn.id()).orElse(turn).complete("STOP", usageJson(usage)));
                        conversations.touch(conv.id());
                    }
                    return Flux.just(new AgentEvent.TurnDone(turn.id(), "STOP", usage));
                }))
                .onErrorResume(e -> {
                    if (finalized.compareAndSet(false, true)) {
                        buffer.flushAll();
                        messages.save(Message.error(turn.id(), buffer.currentSeq(), e.toString()));
                        turns.save(turn.fail("ERROR"));
                    }
                    return Flux.just(new AgentEvent.TurnError(turn.id(), "AGENT_ERROR", e.getMessage()));
                })
                // main 终止（正常/异常/取消）时关闭 side sink，使 merge 得以收口；
                // CANCEL（订阅方断连/中止）：flush 已完成片段 + turn 终态 FAILED/CANCELLED，
                // 不再永久停留 RUNNING。once-guard 抢占失败 = 收尾路径已落终态
                // （COMPLETED/ERROR），晚到的 CANCEL 不改写；RUNNING 复核兜底
                .doFinally(signal -> {
                    sideEvents.tryEmitComplete();
                    if (signal != SignalType.CANCEL) {
                        return;
                    }
                    if (finalized.compareAndSet(false, true)) {
                        buffer.flushAll();
                        turns.findById(turn.id())
                            .filter(t -> "RUNNING".equals(t.status()))
                            .ifPresent(t -> turns.save(t.fail("CANCELLED")));
                    }
                    else {
                        // finalized 已被中断分支消费（turn 已落 WAITING_APPROVAL）而订阅方
                        // 仍断连：等待轮直接转 FAILED，否则 409 前置检查永久锁死该会话。
                        // COMPLETED/FAILED 终态被 filter 拦下——once-guard 语义不变
                        turns.findById(turn.id())
                            .filter(t -> "WAITING_APPROVAL".equals(t.status()))
                            .ifPresent(t -> turns.save(t.fail("CANCELLED_WHILE_WAITING")));
                    }
                });

            return Flux.merge(side, main)
                .startWith(new AgentEvent.Meta(turn.id(), conv.id(), model))
                // 兜底：防止任何路径遗漏 complete 导致订阅悬挂（tryEmitComplete 幂等）
                .doFinally(signal -> sideEvents.tryEmitComplete());
        });
    }

    /** NodeOutput → 事件：LLM 节点流式 chunk → MessageDelta + SegmentBuffer 累积 */
    private Flux<AgentEvent> mapNodeOutput(NodeOutput nodeOutput, Long turnId, SegmentBuffer buffer) {
        if (nodeOutput instanceof StreamingOutput<?> streaming) {
            // SAA 实测：LLM 节点每个 ChatResponse chunk 包装为 StreamingOutput(Message=AssistantMessage)；
            // 无 tool call 的 AssistantMessage.getText() 即正文增量（与 chunk() 提取逻辑一致，规避弃用 API）。
            // 完成事件（AGENT_MODEL 节点收尾）message 为 null，不会造成正文重复。
            if (streaming.message() instanceof AssistantMessage assistantMessage
                && !assistantMessage.hasToolCalls()) {
                String chunk = assistantMessage.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    buffer.appendText(chunk);
                    return Flux.just(new AgentEvent.MessageDelta(turnId, chunk));
                }
            }
        }
        return Flux.empty();
    }

    /**
     * 审批中断分支（Task 5）。落库序：逐项 {@code recordToolCall}（审批卡片数据先于事件持久，
     * arguments 为模型原始 JSON）→ {@code flushAll} → turn WAITING_APPROVAL（finishedAt=null，
     * 非终态）→ 流尾发 {@link AgentEvent.ApprovalRequest}；TurnDone 收尾段据 interrupted 跳过。
     *
     * <p>finalized once-guard 参与抢占：CANCEL 先到（已按 RUNNING 落 FAILED）时本分支整体
     * 放弃，不覆写终态。非 ApprovalHook 产生的中断（verdictFrom 无待审批项）按普通输出
     * 丢弃，走正常 TurnDone 收尾——防御性回退。
     */
    private Flux<AgentEvent> onApprovalInterruption(InterruptionMetadata interruptionMetadata, Turn turn,
                                                    Conversation conv, SegmentBuffer buffer,
                                                    AtomicBoolean interrupted, AtomicBoolean finalized) {
        PermissionRuleEngine.Verdict verdict = ApprovalHook.verdictFrom(interruptionMetadata);
        if (!verdict.needsApproval()) {
            return Flux.empty();
        }
        interrupted.set(true);
        if (!finalized.compareAndSet(false, true)) {
            // CANCEL 已抢先收尾（turn FAILED）：不落 WAITING_APPROVAL 覆写终态，也不发事件
            return Flux.empty();
        }
        for (PermissionRuleEngine.PendingItem item : verdict.items()) {
            buffer.recordToolCall(item.callId(), item.toolName(), item.arguments());
        }
        buffer.flushAll();
        turns.save(turns.findById(turn.id()).orElse(turn).waitingApproval());
        return Flux.just(new AgentEvent.ApprovalRequest(turn.id(), conv.id(), verdict.items()));
    }

    /** usage 形态：ThinkingTap 捕获的 ChatResponse metadata（Spring AI Usage），缺失时归零 */
    private AgentEvent.Usage toUsage(Object usage) {
        if (usage instanceof org.springframework.ai.chat.metadata.Usage u) {
            return new AgentEvent.Usage(
                u.getPromptTokens() == null ? 0 : u.getPromptTokens(),
                u.getCompletionTokens() == null ? 0 : u.getCompletionTokens(),
                u.getTotalTokens() == null ? 0 : u.getTotalTokens());
        }
        return new AgentEvent.Usage(0, 0, 0);
    }

    /**
     * usage 落库形态（turn.usage JSONB 列）：与 AgentEvent.Usage 的 record 字段名
     * 一致（promptTokens/completionTokens/totalTokens），消费方（回放接口/账单统计）
     * 可直接反序列化。
     */
    private String usageJson(AgentEvent.Usage usage) {
        return "{\"promptTokens\":%d,\"completionTokens\":%d,\"totalTokens\":%d}"
            .formatted(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }
}
