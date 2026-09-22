package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.List;

/**
 * BEFORE_MODEL 全量摘要 hook（spec：docs/superpowers/specs/2026-09-22-compaction-hook-design.md）。
 * 超阈值时保最近 K 个完整 turn + 首条 UserMessage，其余经 cache-safe 摘要调用 REPLACE 原地
 * 替换——不换 threadId、不触碰展示存储。摘要失败/超时返回原列表，错误永不进上下文。
 *
 * 可测性设计：AgentCommand.getMessages() 包私有（SAA 外部不可读），核心逻辑收在包可见
 * compact()（null=放行 / 非 null=REPLACE 新列表），beforeModel 仅做 AgentCommand 包装。
 */
@HookPositions(HookPosition.BEFORE_MODEL)
public class SummarizingModelHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(SummarizingModelHook.class);

    static final String SUMMARY_PREFIX = "此前对话摘要：\n";
    static final String COMPACT_INSTRUCTION = """
        请把以上对话历史（含此前的压缩摘要）压缩为一段忠实、信息完备的摘要：保留用户目标、
        已完成的关键操作与结论、未决事项。直接输出摘要正文，不要任何前后缀。""";

    private final ChatModel chatModel;
    private final CompactionSummarySink summarySink;
    private final int thresholdTokens;
    private final int keepTurns;
    private final int charsPerToken;
    private final Duration summaryTimeout;

    public SummarizingModelHook(ChatModel chatModel, CompactionSummarySink summarySink,
                                int thresholdTokens, int keepTurns, int charsPerToken,
                                Duration summaryTimeout) {
        this.chatModel = chatModel;
        this.summarySink = summarySink;
        this.thresholdTokens = thresholdTokens;
        this.keepTurns = keepTurns;
        this.charsPerToken = charsPerToken;
        this.summaryTimeout = summaryTimeout;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        List<Message> compacted = compact(previousMessages, config);
        return compacted == null
            ? new AgentCommand(previousMessages)
            : new AgentCommand(compacted);
    }

    /** null=放行；非null=REPLACE 新列表（Task 2/3 完成切割与摘要） */
    List<Message> compact(List<Message> previousMessages, RunnableConfig config) {
        int totalTokens = estimateTokens(previousMessages);
        if (totalTokens <= thresholdTokens) {
            return null;
        }
        int cutoff = findTurnCutoff(previousMessages);
        if (cutoff <= 0) {
            log.info("[compaction] 跳过：可切割轮次不足 keepTurns={}，估算tokens={} threadId={}",
                keepTurns, totalTokens, config.threadId().orElse(""));
            return null;
        }
        return null; // TODO(task2/task3)：切割与摘要在后续任务实现
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

    private static final int SEARCH_RANGE_FOR_TOOL_PAIRS = 5;

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
            java.util.Set<String> toolCallIds = new java.util.HashSet<>();
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
