package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.linagent.agent.skills.ResidentPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * BEFORE_MODEL 全量摘要 hook（spec：docs/superpowers/specs/2026-09-22-compaction-hook-design.md）。
 * 超阈值时保最近 K 个完整 turn + 首条 UserMessage，其余经 cache-safe 摘要调用 REPLACE 原地
 * 替换——不换 threadId、不触碰展示存储。摘要失败/超时返回原列表，错误永不进上下文。
 *
 * <p>注意：SAA ReactAgent.initGraph 会回写 agent/agentName 字段（跨会话共享单例下互覆）——
 * 本 hook 禁止读取 getAgent()/getAgentName()。
 *
 * <p>可测性设计：AgentCommand.getMessages() 包私有（SAA 外部不可读），核心逻辑收在包可见
 * compact()（null=放行 / 非 null=REPLACE 新列表），beforeModel 仅做 AgentCommand 包装。
 */
@HookPositions(HookPosition.BEFORE_MODEL)
@Component
public class SummarizingModelHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(SummarizingModelHook.class);

    static final String SUMMARY_PREFIX = "此前对话摘要：\n";
    static final String COMPACT_INSTRUCTION = """
        请把以上对话历史（含此前的压缩摘要）压缩为一段忠实、信息完备的摘要：保留用户目标、
        已完成的关键操作与结论、未决事项。直接输出摘要正文，不要任何前后缀。""";
    /** 摘要调用的推理强度：low（StepFun 官方对「摘要/改写/信息抽取」场景的推荐档位） */
    static final String SUMMARY_REASONING_EFFORT = "low";
    private static final int SEARCH_RANGE_FOR_TOOL_PAIRS = 5;

    private final ChatModel chatModel;
    private final CompactionSummarySink summarySink;
    private final ResidentPromptBuilder residentPromptBuilder;
    /** 生效阈值：threshold-tokens 显式配置（>0）优先，否则 = max-context-tokens × trigger-ratio */
    private final int effectiveThresholdTokens;
    /** 模型最大上下文窗口（step-3.7-flash = 262144），用于派生阈值与日志占比 */
    private final long maxContextTokens;
    private final int keepTurns;
    private final int charsPerToken;
    private final Duration summaryTimeout;

    /** Spring 装配构造器（@Value 注入配置）；多构造器并存时必须 @Autowired 指定本构造器 */
    @Autowired
    public SummarizingModelHook(ChatModel chatModel, CompactionSummarySink summarySink,
                                ResidentPromptBuilder residentPromptBuilder,
                                @Value("${agent.compaction.threshold-tokens:-1}") int thresholdTokens,
                                @Value("${agent.compaction.max-context-tokens:262144}") long maxContextTokens,
                                @Value("${agent.compaction.trigger-ratio:0.8}") double triggerRatio,
                                @Value("${agent.compaction.keep-turns:20}") int keepTurns,
                                @Value("${agent.compaction.chars-per-token:2}") int charsPerToken,
                                @Value("${agent.compaction.summary-timeout-seconds:60}") long summaryTimeoutSeconds) {
        this(chatModel, summarySink, residentPromptBuilder, thresholdTokens, maxContextTokens,
            triggerRatio, keepTurns, charsPerToken, Duration.ofSeconds(summaryTimeoutSeconds));
    }

    /** 单测直用构造器（显式 Duration 超时，max-context 取默认）；配置防御在九参构造器收口 */
    public SummarizingModelHook(ChatModel chatModel, CompactionSummarySink summarySink,
                                ResidentPromptBuilder residentPromptBuilder,
                                int thresholdTokens, int keepTurns, int charsPerToken,
                                Duration summaryTimeout) {
        this(chatModel, summarySink, residentPromptBuilder, thresholdTokens, 262144L, 0.8,
            keepTurns, charsPerToken, summaryTimeout);
    }

    /** 全参构造器：thresholdTokens ≤ 0 表示未显式配置，按 max-context × ratio 派生 */
    public SummarizingModelHook(ChatModel chatModel, CompactionSummarySink summarySink,
                                ResidentPromptBuilder residentPromptBuilder,
                                int thresholdTokens, long maxContextTokens, double triggerRatio,
                                int keepTurns, int charsPerToken, Duration summaryTimeout) {
        if (keepTurns < 1) {
            throw new IllegalArgumentException("agent.compaction.keep-turns 必须 >= 1");
        }
        if (charsPerToken < 1) {
            throw new IllegalArgumentException("agent.compaction.chars-per-token 必须 >= 1");
        }
        if (summaryTimeout.isZero() || summaryTimeout.isNegative()) {
            throw new IllegalArgumentException("agent.compaction.summary-timeout-seconds 必须为正");
        }
        if (thresholdTokens <= 0 && (triggerRatio <= 0 || maxContextTokens <= 0)) {
            throw new IllegalArgumentException(
                "threshold-tokens 未配置（≤0）时 max-context-tokens 与 trigger-ratio 必须为正");
        }
        this.chatModel = chatModel;
        this.summarySink = summarySink;
        this.residentPromptBuilder = residentPromptBuilder;
        this.maxContextTokens = maxContextTokens;
        this.effectiveThresholdTokens = thresholdTokens > 0
            ? thresholdTokens
            : (int) (maxContextTokens * triggerRatio);
        this.keepTurns = keepTurns;
        this.charsPerToken = charsPerToken;
        this.summaryTimeout = summaryTimeout;
    }

    /** 生效阈值（包可见供单测）：显式 threshold-tokens 优先，否则 max-context × trigger-ratio */
    int effectiveThresholdTokens() {
        return effectiveThresholdTokens;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        List<Message> compacted = compact(previousMessages, config);
        return compacted == null
            ? new AgentCommand(previousMessages)
            : new AgentCommand(compacted);
    }

    /** null=放行；非null=REPLACE 新列表 [SystemMessage(摘要), 首条UserMessage?, ...保留区] */
    List<Message> compact(List<Message> previousMessages, RunnableConfig config) {
        int totalTokens = estimateTokens(previousMessages);
        if (totalTokens <= effectiveThresholdTokens) {
            return null;
        }
        int cutoff = findTurnCutoff(previousMessages);
        if (cutoff <= 0) {
            log.info("[compaction] 跳过：可切割轮次不足 keepTurns={}，估算tokens={}/{} ({}%) 阈值={} threadId={}",
                keepTurns, totalTokens, maxContextTokens, percentOf(totalTokens), effectiveThresholdTokens,
                config.threadId().orElse(""));
            return null;
        }
        UserMessage firstUser = null;
        for (Message m : previousMessages) {
            if (m instanceof UserMessage u) {
                firstUser = u;
                break;
            }
        }
        // 幂等守卫：被摘区仅剩旧摘要 SystemMessage（与首条用户消息）时重摘无信息增量——
        // 深工具轮的保留区顶住阈值时会陷入「每步触发、收缩恒零」的重复摘要循环，直接放行
        boolean hasOriginalContent = false;
        for (int i = 0; i < cutoff; i++) {
            Message m = previousMessages.get(i);
            if (m != firstUser && !(m instanceof SystemMessage)) {
                hasOriginalContent = true;
                break;
            }
        }
        if (!hasOriginalContent) {
            log.info("[compaction] 跳过：被摘区无原文（仅旧摘要/首条用户消息），重摘无增量 "
                    + "估算tokens={}/{} ({}%) threadId={}",
                totalTokens, maxContextTokens, percentOf(totalTokens), config.threadId().orElse(""));
            return null;
        }

        int messagesBefore = previousMessages.size();
        long start = System.currentTimeMillis();
        String summary = summarize(previousMessages, cutoff, config);
        if (summary == null || summary.isBlank()) {
            return null; // 宁可超长不丢记忆
        }

        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new SystemMessage(SUMMARY_PREFIX + summary));
        if (firstUser != null && previousMessages.indexOf(firstUser) < cutoff) {
            newMessages.add(firstUser);
        }
        newMessages.addAll(previousMessages.subList(cutoff, previousMessages.size()));

        log.info("[compaction] threadId={} 估算tokens={}/{} ({}%) 阈值={} 消息 {}→{} 摘要耗时={}ms summaryChars={}",
            config.threadId().orElse(""), totalTokens, maxContextTokens, percentOf(totalTokens),
            effectiveThresholdTokens, messagesBefore,
            newMessages.size(), System.currentTimeMillis() - start, summary.length());
        try {
            summarySink.onSummary(new CompactionSummarySink.SummaryContext(
                config.threadId().orElse(""), summary, messagesBefore, newMessages.size()));
        } catch (Exception e) {
            log.warn("[compaction] summarySink 失败（忽略，不影响压缩结果）threadId={} 原因={}",
                config.threadId().orElse(""), e.getMessage());
        }
        return newMessages;
    }

    /** 估算 tokens 占最大上下文的百分比（日志展示；+1 防御总量为 0 时除零） */
    private int percentOf(long totalTokens) {
        return (int) (totalTokens * 100 / (maxContextTokens + 1));
    }

    /**
     * cache-safe 摘要：请求 = [SystemMessage(systemPrompt), msg[0..cutoff) 原样（含首条 UserMessage）,
     * 尾部压缩指令]——与主调用 [systemPrompt, u0, a0, u1, ...] 从首位起前缀对齐（StepFun 前缀缓存
     * 从首 token 比对）；失败/超时返回 null。
     * 摘要属轻量抽取任务，reasoning_effort=low 压掉深思考耗时（StepFun 官方：low 适用摘要场景）。
     */
    private String summarize(List<Message> previousMessages, int cutoff, RunnableConfig config) {
        List<Message> request = new ArrayList<>();
        request.add(new SystemMessage(residentPromptBuilder.build()));
        request.addAll(previousMessages.subList(0, cutoff));
        request.add(new UserMessage(COMPACT_INSTRUCTION));
        Prompt summaryPrompt = new Prompt(request, org.springframework.ai.openai.OpenAiChatOptions.builder()
            .reasoningEffort(SUMMARY_REASONING_EFFORT)
            .build());
        // block() 在 Reactor NonBlocking 线程会立刻抛 IllegalStateException（静默劣化为压缩永久
        // 失败）；CompletableFuture.get 无此检查。超时后 cancel(true) 通知下游放弃迟到结果。
        CompletableFuture<ChatResponse> future = Mono.fromCallable(() -> chatModel.call(summaryPrompt))
            .subscribeOn(Schedulers.boundedElastic())
            .toFuture();
        try {
            ChatResponse resp = future.get(summaryTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (resp != null && resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                log.info("[compaction] 摘要调用 usage={}（验证 cache-safe 命中看 cached/prompt 比值）",
                    resp.getMetadata().getUsage());
            }
            return resp == null ? null : resp.getResult().getOutput().getText();
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[compaction] 摘要超时（本轮跳过，下轮重试）threadId={} 超时={}",
                config.threadId().orElse(""), summaryTimeout);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("[compaction] 摘要等待被中断（本轮跳过，下轮重试）threadId={}",
                config.threadId().orElse(""));
            return null;
        } catch (ExecutionException e) {
            log.warn("[compaction] 摘要失败（本轮跳过，下轮重试）threadId={} 原因={}",
                config.threadId().orElse(""), e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[compaction] 摘要失败（本轮跳过，下轮重试）threadId={} 原因={}",
                config.threadId().orElse(""), e.getMessage());
            return null;
        }
    }

    /** 估算口径：移植 SAA TokenCounter.approximateMsgCounter（含 ToolResponse 数据与 toolCall arguments） */
    int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    total += response.responseData().length() / charsPerToken;
                }
            } else if (msg instanceof AssistantMessage assistantMessage) {
                if (msg.getText() != null) {
                    total += msg.getText().length() / charsPerToken;
                }
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    total += toolCall.arguments().length() / charsPerToken;
                }
            } else if (msg.getText() != null) {
                total += msg.getText().length() / charsPerToken;
            }
        }
        return total;
    }

    /** 切割点 = 倒数第 keepTurns 个 UserMessage 的下标；配对不安全时向左回退；不足 K 轮返回 0 */
    int findTurnCutoff(List<Message> messages) {
        int turnStarts = 0;
        int cutoff = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                turnStarts++;
                if (turnStarts == keepTurns) {
                    cutoff = i;
                    break;
                }
            }
        }
        if (cutoff < 0) {
            return 0;
        }
        while (cutoff > 0 && !isSafeCutoffPoint(messages, cutoff)) {
            cutoff--;
        }
        return cutoff;
    }

    /** 切割点 k：[0,k) 被摘、[k,*) 保留；不得使 AssistantMessage(toolCalls) 与其 ToolResponseMessage 分居两侧 */
    boolean isSafeCutoffPoint(List<Message> messages, int cutoffIndex) {
        if (cutoffIndex >= messages.size()) {
            return true;
        }
        int searchStart = Math.max(0, cutoffIndex - SEARCH_RANGE_FOR_TOOL_PAIRS);
        int searchEnd = Math.min(messages.size(), cutoffIndex + SEARCH_RANGE_FOR_TOOL_PAIRS);
        for (int i = searchStart; i < searchEnd; i++) {
            Message msg = messages.get(i);
            if (!(msg instanceof AssistantMessage assistantMessage) || assistantMessage.getToolCalls().isEmpty()) {
                continue;
            }
            Set<String> toolCallIds = new HashSet<>();
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                toolCallIds.add(toolCall.id());
            }
            for (int j = i + 1; j < messages.size(); j++) {
                if (messages.get(j) instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        if (toolCallIds.contains(response.id())) {
                            boolean aiBefore = i < cutoffIndex;
                            boolean toolBefore = j < cutoffIndex;
                            if (aiBefore != toolBefore) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public String getName() {
        return "SummarizingHook";
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of();
    }
}
