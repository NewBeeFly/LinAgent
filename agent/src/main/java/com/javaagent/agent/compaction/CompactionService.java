package com.javaagent.agent.compaction;

import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.Turn;
import com.javaagent.agent.persistence.TurnRepository;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 历史超阈值时压缩"给模型的记忆"（新 threadId + 摘要落 conversation.compact_summary），
 * 展示存储（turn/message）不动（spec §6，Errata 2：不建引导轮）。
 *
 * fix round 1：估算基准与给模型的记忆同量纲——既有摘要体量 + 压缩锚点
 * （compacted_turn_seq）之后的新增消息体量；已摘要过的展示历史不重复计入、
 * 不重摘（LLM 请求只含增量 + 既有摘要作为合并上下文）。
 * 摘要后锚点推进到当前最大 turn seq，避免每轮重复触发压缩。
 */
@Service
public class CompactionService {

    private static final String SUMMARY_SYSTEM_PROMPT = """
        你是对话历史压缩器。把「此前压缩摘要」与「压缩锚点之后的对话历史」（含思考与
        工具调用记录）合并压缩为一段忠实、信息完备的摘要：保留用户目标、已做过的关键
        操作与结论、未决事项。直接输出摘要正文，不要任何前后缀。
        """;

    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final ChatModel chatModel;
    private final int thresholdTokens;

    public CompactionService(ConversationRepository conversations, TurnRepository turns,
                             MessageRepository messages, ChatModel chatModel,
                             @Value("${agent.compaction.threshold-tokens:24000}") int thresholdTokens) {
        this.conversations = conversations;
        this.turns = turns;
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
        int anchor = conv.compactedTurnSeq() == null ? 0 : conv.compactedTurnSeq();
        List<Turn> convTurns = turns.findByConversationIdOrderBySeqAsc(conversationId);
        Map<Long, Integer> turnSeqById = new HashMap<>();
        convTurns.forEach(t -> turnSeqById.put(t.id(), t.seq()));

        // 增量 = 锚点之后的新增消息（已摘要的展示历史不重摘）
        List<Message> incremental = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conversationId)
            .stream()
            .filter(m -> turnSeqById.getOrDefault(m.turnId(), anchor) > anchor)
            .toList();

        // 估算基准 = 既有摘要 + 增量（与给模型的记忆同量纲）
        long estimatedTokens = (conv.compactSummary() == null ? 0 : conv.compactSummary().length() / 4)
            + incremental.stream()
            .mapToLong(m -> ((m.content() == null ? 0 : m.content().length())
                + (m.result() == null ? 0 : m.result().length())) / 4)
            .sum();
        if (estimatedTokens <= thresholdTokens || incremental.isEmpty()) {
            // 无增量时不空转换代（摘要自身超限但没有新内容，无可摘）
            return Optional.empty();
        }

        String transcript = renderTranscript(conv.compactSummary(), incremental);
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
        int maxTurnSeq = convTurns.isEmpty() ? anchor : convTurns.get(convTurns.size() - 1).seq();

        conversations.save(new Conversation(conv.id(), conv.title(), newThreadId, summary,
            maxTurnSeq, conv.createdAt(), Instant.now()));
        return Optional.of(newThreadId);
    }

    /** LLM 请求载荷：既有摘要（合并上下文）+ 锚点之后的增量对话（原始历史不重摘） */
    private String renderTranscript(String priorSummary, List<Message> incremental) {
        StringBuilder sb = new StringBuilder();
        if (priorSummary != null && !priorSummary.isBlank()) {
            sb.append("【此前压缩摘要】\n").append(priorSummary).append("\n\n");
        }
        sb.append("【压缩锚点之后的完整对话历史】\n\n");
        for (Message m : incremental) {
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
