package com.javaagent.agent.compaction;

import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 历史超阈值时压缩"给模型的记忆"（新 threadId + 摘要落 conversation.compact_summary），
 * 展示存储（turn/message）不动（spec §6，Errata 2：不建引导轮）。
 *
 * 压缩后记忆重建由 AgentFacade 承担：chat 时把 compact_summary 以前置
 * SystemMessage 发给 agent（新 threadId 的 checkpoint 从摘要起步）。
 */
@Service
public class CompactionService {

    private static final String SUMMARY_SYSTEM_PROMPT = """
        你是对话历史压缩器。把给定的多轮对话（含思考与工具调用记录）压缩为一段
        忠实、信息完备的摘要：保留用户目标、已做过的关键操作与结论、未决事项。
        直接输出摘要正文，不要任何前后缀。
        """;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ChatModel chatModel;
    private final int thresholdTokens;

    public CompactionService(ConversationRepository conversations, MessageRepository messages,
                             ChatModel chatModel,
                             @Value("${agent.compaction.threshold-tokens:24000}") int thresholdTokens) {
        this.conversations = conversations;
        this.messages = messages;
        this.chatModel = chatModel;
        this.thresholdTokens = thresholdTokens;
    }

    /**
     * 超阈值时生成摘要并切换新 threadId（conv-{id}-v{n}），返回新 threadId；否则返回 empty。
     */
    public Optional<String> compactIfNeeded(Long conversationId) {
        Conversation conv = conversations.findById(conversationId).orElse(null);
        if (conv == null) {
            return Optional.empty();
        }
        List<Message> history = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conversationId);
        long estimatedTokens = history.stream()
            .mapToLong(m -> ((m.content() == null ? 0 : m.content().length())
                + (m.result() == null ? 0 : m.result().length())) / 4)
            .sum();
        if (estimatedTokens <= thresholdTokens) {
            return Optional.empty();
        }

        String transcript = renderTranscript(history);
        String summary = chatModel.call(new Prompt(List.of(
                new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                new UserMessage(transcript))))
            .getResult().getOutput().getText();
        if (summary == null || summary.isBlank()) {
            // 摘要缺失时不切换线程（保守：宁可超长也不丢记忆）
            return Optional.empty();
        }

        int version = parseVersion(conv.threadId(), conversationId);
        String newThreadId = "conv-" + conversationId + "-v" + (version + 1);

        conversations.save(new Conversation(conv.id(), conv.title(), newThreadId, summary,
            conv.createdAt(), Instant.now()));
        return Optional.of(newThreadId);
    }

    private String renderTranscript(List<Message> history) {
        StringBuilder sb = new StringBuilder("以下是完整对话历史：\n\n");
        for (Message m : history) {
            String body = switch (m.msgType()) {
                case "USER" -> "用户: " + m.content();
                case "THINKING" -> "(思考) " + m.content();
                case "TEXT" -> "助手: " + m.content();
                case "TOOL_CALL" -> "助手调用工具 " + m.toolName() + " 入参 " + m.arguments();
                case "TOOL_RESULT" -> "工具 " + m.toolName() + " 返回 " + truncate(m.result());
                default -> "";
            };
            if (body != null && !body.isEmpty()) {
                sb.append(body).append('\n');
            }
        }
        return sb.toString();
    }

    private int parseVersion(String threadId, Long conversationId) {
        String prefix = "conv-" + conversationId + "-v";
        if (threadId != null && threadId.startsWith(prefix)) {
            try {
                return Integer.parseInt(threadId.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 2000 ? s.substring(0, 2000) + "...[截断]" : s;
    }
}
