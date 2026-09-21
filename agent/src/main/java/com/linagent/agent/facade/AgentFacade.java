package com.linagent.agent.facade;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.linagent.agent.agent.AgentFactory;
import com.linagent.agent.agent.EventEmittingToolInterceptor;
import com.linagent.agent.compaction.CompactionService;
import com.linagent.agent.persistence.Conversation;
import com.linagent.agent.persistence.ConversationRepository;
import com.linagent.agent.persistence.Message;
import com.linagent.agent.persistence.MessageRepository;
import com.linagent.agent.persistence.Turn;
import com.linagent.agent.persistence.TurnRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.List;
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
 * side sink 的 complete 由 main 流终止时触发（doFinally），外层 doFinally 仅兜底，
 * 避免 merge 因 side 永不终止而悬挂。
 */
@Service
public class AgentFacade {

    private final AgentFactory agentFactory;
    private final CompactionService compactionService;
    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final String model;

    public AgentFacade(AgentFactory agentFactory, CompactionService compactionService,
                       ConversationRepository conversations, TurnRepository turns,
                       MessageRepository messages,
                       @Value("${spring.ai.openai.chat.options.model:step-3.7-flash}") String model) {
        this.agentFactory = agentFactory;
        this.compactionService = compactionService;
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.model = model;
    }

    public Flux<AgentEvent> chat(Long conversationId, String content) {
        return Flux.defer(() -> {
            // 压缩检查在读取会话之前（UserMessage 不进本次压缩摘要），超阈值时切换新 threadId
            compactionService.compactIfNeeded(conversationId);
            // 压缩后 threadId/compact_summary 已更新，必须重新读取
            Conversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));

            int turnSeq = turns.countByConversationId(conv.id()) + 1;
            Turn turn = turns.save(Turn.running(conv.id(), turnSeq));
            messages.save(Message.user(turn.id(), 0, content));

            SegmentBuffer buffer = new SegmentBuffer(turn.id(), messages);
            // 终态收口 once-guard（fix round 1）：flushAll→终态落库在 完成/异常/取消 三条
            // 路径中只执行一次——CANCEL 与末条 TurnDone 发射竞态时不会把 COMPLETED 覆写为
            // FAILED；flushAll 的跨线程原子性由 SegmentBuffer 自身 synchronized 保证
            AtomicBoolean finalized = new AtomicBoolean(false);
            // 三路发射方（ThinkingTap / 工具拦截器，SAA 并行 tool call 时多线程）共享同一
            // unicast sink：经 SerializedEmitSink 串行收口，避免 FAIL_NON_SERIALIZED 静默丢事件
            Sinks.Many<Object> sideEvents =
                new SerializedEmitSink(Sinks.many().unicast().onBackpressureBuffer());
            AtomicReference<Object> usageCapture = new AtomicReference<>();

            EventEmittingToolInterceptor toolInterceptor =
                new EventEmittingToolInterceptor(sideEvents, buffer, turn.id());
            AgentFactory.AgentHandle handle = agentFactory.create(sideEvents, usageCapture, toolInterceptor);

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
            // 压缩过的会话（compact_summary 非空）把摘要以前置 SystemMessage 常驻输入
            // （SAA stream 同时支持 UserMessage 与 List<Message> 重载，List 路径经源码核实
            //  会完整进入 graph state 的 messages，末条 UserMessage 提取为 input）
            Flux<NodeOutput> nodeOutputs;
            try {
                if (conv.compactSummary() == null || conv.compactSummary().isBlank()) {
                    nodeOutputs = handle.agent().stream(new UserMessage(content), config);
                } else {
                    nodeOutputs = handle.agent().stream(List.of(
                        new SystemMessage("此前对话摘要：" + conv.compactSummary()),
                        new UserMessage(content)), config);
                }
            } catch (GraphRunnerException e) {
                nodeOutputs = Flux.error(e);
            }

            Flux<AgentEvent> main = nodeOutputs
                .concatMap(nodeOutput -> mapNodeOutput(nodeOutput, turn.id(), buffer))
                .concatWith(Flux.defer(() -> {
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
                    if (signal == SignalType.CANCEL && finalized.compareAndSet(false, true)) {
                        buffer.flushAll();
                        turns.findById(turn.id())
                            .filter(t -> "RUNNING".equals(t.status()))
                            .ifPresent(t -> turns.save(t.fail("CANCELLED")));
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
