package com.javaagent.agent.compaction;

import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
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
 * Errata 2 形态：summary 落 conversation.compact_summary 列（不建引导轮、
 * 不写 turn/message 表），compactIfNeeded 返回新 threadId（conv-{id}-v{n}）。
 */
class CompactionServiceTest {

    private ConversationRepository conversations;
    private MessageRepository messages;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(MessageRepository.class);
        chatModel = mock(ChatModel.class);
    }

    @Test
    void underThresholdReturnsEmptyWithoutSideEffects() {
        when(conversations.findById(1L)).thenReturn(Optional.of(
            new Conversation(1L, "t", "conv-1", null, Instant.now(), Instant.now())));
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(
            List.of(new Message(1L, 1L, 0, "USER", "短对话", null, null, null, null, null, null, Instant.now())));

        CompactionService service = new CompactionService(conversations, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
        verify(conversations, never()).save(any());
    }

    @Test
    void overThresholdSummarizesAndPersistsCompactSummaryWithNewThreadId() {
        Conversation conv = new Conversation(1L, "t", "conv-1", null, Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));

        // 构造超阈值历史：两段 ~100k 字符的消息（约 50k tokens > 24000）
        String big = "x".repeat(100_000);
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            new Message(1L, 1L, 0, "USER", big, null, null, null, null, null, null, Instant.now()),
            new Message(2L, 1L, 1, "TEXT", big, null, null, null, null, null, null, Instant.now())));

        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("这是历史摘要")))));
        when(conversations.save(any())).thenReturn(conv);

        CompactionService service = new CompactionService(conversations, messages, chatModel, 24000);

        Optional<String> newThreadId = service.compactIfNeeded(1L);

        assertThat(newThreadId).contains("conv-1-v1");

        // compact_summary 落库（Errata 2：不建引导轮，turn/message 展示存储不动）
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().compactSummary()).isEqualTo("这是历史摘要");
        assertThat(saved.getValue().threadId()).isEqualTo("conv-1-v1");
        verify(messages, never()).save(any());

        // 摘要请求里包含原始历史内容（UserMessage 载荷，SystemMessage 为压缩指令）
        verify(chatModel).call(org.mockito.ArgumentMatchers.argThat(
            (Prompt p) -> p.getUserMessage().getText().contains(big.substring(0, 100))));
    }

    @Test
    void versionedThreadIncrementsVersion() {
        Conversation conv = new Conversation(1L, "t", "conv-1-v2", "旧摘要", Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));

        String big = "x".repeat(100_000);
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            new Message(1L, 1L, 0, "USER", big, null, null, null, null, null, null, Instant.now())));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("新摘要")))));
        when(conversations.save(any())).thenReturn(conv);

        CompactionService service = new CompactionService(conversations, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).contains("conv-1-v3");
    }

    @Test
    void missingConversationReturnsEmpty() {
        when(conversations.findById(404L)).thenReturn(Optional.empty());

        CompactionService service = new CompactionService(conversations, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(404L)).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void toolResultContentCountsTowardEstimate() {
        when(conversations.findById(1L)).thenReturn(Optional.of(
            new Conversation(1L, "t", "conv-1", null, Instant.now(), Instant.now())));
        // content null、result 100k 字符 → 25k tokens > 24000
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            new Message(1L, 1L, 0, "TOOL_RESULT", null, "c1", "list_dir", null,
                "y".repeat(100_000), true, 10L, Instant.now())));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("摘要")))));
        when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompactionService service = new CompactionService(conversations, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).contains("conv-1-v1");
    }
}
