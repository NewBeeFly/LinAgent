package com.javaagent.agent.compaction;

import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.Turn;
import com.javaagent.agent.persistence.TurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Errata 2 + fix round 1：summary 落 conversation.compact_summary 列（不建引导轮），
 * compacted_turn_seq 为压缩锚点——估算基准与给模型的记忆同一量纲：
 * 既有摘要 + 锚点之后的新增消息（已摘要过的展示历史不重复计入、不重摘）。
 */
class CompactionServiceTest {

    private ConversationRepository conversations;
    private TurnRepository turns;
    private MessageRepository messages;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        turns = mock(TurnRepository.class);
        messages = mock(MessageRepository.class);
        chatModel = mock(ChatModel.class);
    }

    @Test
    void underThresholdReturnsEmptyWithoutSideEffects() {
        when(conversations.findById(1L)).thenReturn(Optional.of(
            new Conversation(1L, "t", "conv-1", null, 0, Instant.now(), Instant.now())));
        when(turns.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of(turn(1L, 1)));
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(
            List.of(msg(1L, 0, "USER", "短对话")));

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
        verify(conversations, never()).save(any());
    }

    @Test
    void overThresholdSummarizesAndPersistsCompactSummaryWithNewThreadId() {
        Conversation conv = new Conversation(1L, "t", "conv-1", null, 0, Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));
        when(turns.findByConversationIdOrderBySeqAsc(1L))
            .thenReturn(List.of(turn(1L, 1), turn(2L, 2)));

        // 构造超阈值历史：两段 ~100k 字符的消息（约 50k tokens > 24000）
        String big = "x".repeat(100_000);
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            msg(1L, 0, "USER", big), msg(2L, 1, "TEXT", big)));

        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("这是历史摘要")))));
        when(conversations.save(any())).thenReturn(conv);

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        Optional<String> newThreadId = service.compactIfNeeded(1L);

        assertThat(newThreadId).contains("conv-1-v1");

        // compact_summary 落库 + 锚点推进到当前最大 turn seq（Errata 2：不建引导轮，展示存储不动）
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().compactSummary()).isEqualTo("这是历史摘要");
        assertThat(saved.getValue().threadId()).isEqualTo("conv-1-v1");
        assertThat(saved.getValue().compactedTurnSeq()).isEqualTo(2);
        verify(messages, never()).save(any());

        // 摘要请求里包含原始历史内容（UserMessage 载荷，SystemMessage 为压缩指令）
        verify(chatModel).call(org.mockito.ArgumentMatchers.argThat(
            (Prompt p) -> p.getUserMessage().getText().contains(big.substring(0, 100))));
    }

    @Test
    void versionedThreadIncrementsVersion() {
        Conversation conv = new Conversation(1L, "t", "conv-1-v2", "旧摘要", 2, Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));
        when(turns.findByConversationIdOrderBySeqAsc(1L))
            .thenReturn(List.of(turn(1L, 1), turn(2L, 2), turn(3L, 3)));

        String big = "z".repeat(100_000);
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(
            List.of(msg(3L, 0, "USER", big)));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("新摘要")))));
        when(conversations.save(any())).thenReturn(conv);

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        Optional<String> result = service.compactIfNeeded(1L);

        assertThat(result).contains("conv-1-v3");
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().compactedTurnSeq()).isEqualTo(3);
    }

    /**
     * fix round 1 核心：压缩后新增少量消息（未超阈值）不再触发第二次 LLM 摘要调用
     * ——已摘要的大体量展示历史不计入估算基准。
     */
    @Test
    void incrementalUnderThresholdDoesNotRecompact() {
        Conversation conv = new Conversation(1L, "t", "conv-1-v1", "既有摘要", 2, Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));
        when(turns.findByConversationIdOrderBySeqAsc(1L))
            .thenReturn(List.of(turn(1L, 1), turn(2L, 2), turn(3L, 3)));

        // 锚点前的大体量历史（不重摘、不计入估算）+ 锚点后的少量新增
        String oldBig = "x".repeat(100_000);
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            msg(1L, 0, "USER", oldBig),
            msg(2L, 1, "TEXT", oldBig),
            msg(3L, 0, "USER", "新增的小问题")));

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
        verify(conversations, never()).save(any());
    }

    /**
     * fix round 1：新增消息超阈值才触发第二次压缩，且摘要请求只含增量
     * （不含锚点前已摘要的原始文本；既有摘要作为上下文合并进新摘要）。
     */
    @Test
    void incrementalOverThresholdRecompactsWithOnlyNewContent() {
        Conversation conv = new Conversation(1L, "t", "conv-1-v1", "既有摘要", 2, Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));
        when(turns.findByConversationIdOrderBySeqAsc(1L))
            .thenReturn(List.of(turn(1L, 1), turn(2L, 2), turn(3L, 3)));

        String oldBig = "x".repeat(100_000);
        String newBig = "z".repeat(100_000);
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            msg(1L, 0, "USER", oldBig),
            msg(3L, 0, "USER", newBig)));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("合并后的新摘要")))));
        when(conversations.save(any())).thenReturn(conv);

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        Optional<String> result = service.compactIfNeeded(1L);

        assertThat(result).contains("conv-1-v2");
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().compactSummary()).isEqualTo("合并后的新摘要");
        assertThat(saved.getValue().compactedTurnSeq()).isEqualTo(3);

        // 第二次摘要请求：含新增内容、既有摘要（合并上下文），不含已摘要的原始文本
        verify(chatModel).call(org.mockito.ArgumentMatchers.argThat(
            (Prompt p) -> {
                String text = p.getUserMessage().getText();
                return text.contains(newBig.substring(0, 100))
                    && text.contains("既有摘要")
                    && !text.contains(oldBig.substring(0, 100));
            }));
    }

    /** 摘要自身超限但无新增消息：无可摘内容，不切线程（防止空转循环换代） */
    @Test
    void summaryOnlyOverThresholdWithoutIncrementReturnsEmpty() {
        Conversation conv = new Conversation(1L, "t", "conv-1-v1", "s".repeat(100_000), 5,
            Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));
        when(turns.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of(turn(1L, 1)));
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(
            List.of(msg(1L, 0, "USER", "旧消息")));

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void missingConversationReturnsEmpty() {
        when(conversations.findById(404L)).thenReturn(Optional.empty());

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(404L)).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void toolResultContentCountsTowardEstimate() {
        when(conversations.findById(1L)).thenReturn(Optional.of(
            new Conversation(1L, "t", "conv-1", null, 0, Instant.now(), Instant.now())));
        when(turns.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of(turn(1L, 1)));
        // content null、result 100k 字符 → 25k tokens > 24000
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            new Message(1L, 1L, 0, "TOOL_RESULT", null, "c1", "list_dir", null,
                "y".repeat(100_000), true, 10L, Instant.now())));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("摘要")))));
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).contains("conv-1-v1");
    }

    // ---- 构造桩 ----

    private Turn turn(long id, int seq) {
        return new Turn(id, 1L, seq, "COMPLETED", null, null, Instant.now(), Instant.now());
    }

    private Message msg(long turnId, int seq, String type, String content) {
        return new Message(null, turnId, seq, type, content, null, null, null, null, null, null, Instant.now());
    }
}
