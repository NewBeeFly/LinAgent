package com.linagent.web.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.linagent.agent.facade.AgentFacade;
import com.linagent.agent.persistence.CheckpointCleaner;
import com.linagent.agent.persistence.ConversationRepository;
import com.linagent.agent.persistence.MessageRepository;
import com.linagent.agent.persistence.TurnRepository;
import com.linagent.web.auth.RequestAuthenticator;
import com.linagent.web.support.TestAuth;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConversationController.class)
class ConversationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;
    @MockBean CheckpointCleaner checkpointCleaner;
    /** 鉴权链（Task 3）：@WebMvcTest 自动装配 AuthContextFilter，RequestAuthenticator
     *  不在切片内须 @MockBean 打桩（MockMvc 无 defaultHeaders，请求统一带 authHeaders） */
    @MockBean RequestAuthenticator authenticator;

    /** 默认身份 header：由 TestAuth.LINMJ 构造，所有 perform 统一带上 */
    private final HttpHeaders authHeaders = buildAuthHeaders();

    private static HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        TestAuth.LINMJ.accept(headers);
        return headers;
    }

    @BeforeEach
    void auth() {
        when(authenticator.authenticate("default", "linmj"))
            .thenReturn(Optional.of(TestAuth.LINMJ_CTX));
    }

    @Test
    void createReturnsConversation() throws Exception {
        // 两段式落库语义：save 幂等桩——id 为 null 时分配 id（第一次 INSERT），
        // 已有 id 时原样返回（第二次 UPDATE）
        when(conversations.save(any())).thenAnswer(inv -> {
            com.linagent.agent.persistence.Conversation e = inv.getArgument(0);
            if (e.id() == null) {
                return new com.linagent.agent.persistence.Conversation(1L, e.title(), e.threadId(),
                    e.compactSummary(), e.compactedTurnSeq(), e.tenantId(), e.userId(),
                    e.createdAt(), e.updatedAt());
            }
            return e;
        });

        mockMvc.perform(post("/api/conversations").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新会话\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.title").value("新会话"));
    }

    /**
     * 终审 Important 1：create 两段式落库——先 save 拿 id，再落 threadId = "conv-{id}"。
     * nanoTime 形态的 threadId 与 CheckpointCleaner 的删除模式（conv-{id} / conv-{id}-v%）
     * 匹配不上，导致未压缩会话的 checkpoint 永久孤儿。
     */
    @Test
    void createPersistsThreadIdEqualToConvIdPrefix() throws Exception {
        when(conversations.save(any())).thenAnswer(inv -> {
            com.linagent.agent.persistence.Conversation e = inv.getArgument(0);
            if (e.id() == null) {
                return new com.linagent.agent.persistence.Conversation(1L, e.title(), e.threadId(),
                    e.compactSummary(), e.compactedTurnSeq(), e.tenantId(), e.userId(),
                    e.createdAt(), e.updatedAt());
            }
            return e;
        });

        mockMvc.perform(post("/api/conversations").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.linagent.agent.persistence.Conversation> captor =
            org.mockito.ArgumentCaptor.forClass(com.linagent.agent.persistence.Conversation.class);
        verify(conversations, times(2)).save(captor.capture());
        // 第二段：threadId 与会话 id 对齐（spec §3 threadId=conversationId 语义）
        assertThat(captor.getAllValues().get(1).threadId()).isEqualTo("conv-1");
        // 归属随鉴权身份注入（多租户 v0.2：ctx.tenantId/ctx.userId）
        assertThat(captor.getAllValues().get(1).tenantId()).isEqualTo("default");
        assertThat(captor.getAllValues().get(1).userId()).isEqualTo("linmj");
    }

    @Test
    void listReturnsConversationsWithTurnCount() throws Exception {
        when(conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc(any(), any())).thenReturn(List.of(
            new com.linagent.agent.persistence.Conversation(1L, "会话A", "conv-1", null, 0, "default", "linmj",
                java.time.Instant.now(), java.time.Instant.now())));
        when(turns.countByConversationId(1L)).thenReturn(3);

        mockMvc.perform(get("/api/conversations").headers(authHeaders))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].turnCount").value(3));
    }

    @Test
    void turnsReplayReturnsNestedMessages() throws Exception {
        when(conversations.findByIdAndTenantIdAndUserId(eq(1L), any(), any()))
            .thenReturn(java.util.Optional.of(
                new com.linagent.agent.persistence.Conversation(1L, "会话A", "conv-1", null, 0,
                    "default", "linmj", java.time.Instant.now(), java.time.Instant.now())));
        when(turns.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of(
            new com.linagent.agent.persistence.Turn(10L, 1L, 1, "COMPLETED", "STOP", null,
                java.time.Instant.now(), java.time.Instant.now())));
        when(messages.findByTurnIdOrderBySeq(10L)).thenReturn(List.of(
            new com.linagent.agent.persistence.Message(1L, 10L, 0, "USER", "问题", null, null, null, null, null, null, java.time.Instant.now()),
            new com.linagent.agent.persistence.Message(2L, 10L, 1, "TEXT", "回答", null, null, null, null, null, null, java.time.Instant.now())));

        mockMvc.perform(get("/api/conversations/1/turns").headers(authHeaders))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].messages[0].msgType").value("USER"))
            .andExpect(jsonPath("$[0].messages[1].msgType").value("TEXT"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        when(conversations.findByIdAndTenantIdAndUserId(eq(1L), any(), any()))
            .thenReturn(java.util.Optional.of(
                new com.linagent.agent.persistence.Conversation(1L, "会话A", "conv-1", null, 0,
                    "default", "linmj", java.time.Instant.now(), java.time.Instant.now())));

        mockMvc.perform(delete("/api/conversations/1").headers(authHeaders))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteCascadesCheckpointCleanupThenRemovesConversation() throws Exception {
        when(conversations.findByIdAndTenantIdAndUserId(eq(1L), any(), any()))
            .thenReturn(java.util.Optional.of(
                new com.linagent.agent.persistence.Conversation(1L, "会话A", "conv-1", null, 0,
                    "default", "linmj", java.time.Instant.now(), java.time.Instant.now())));

        mockMvc.perform(delete("/api/conversations/1").headers(authHeaders))
            .andExpect(status().isNoContent());

        // checkpoint 级联清理（Errata 3）：按会话 id 清 conv-1 / conv-1-v* 线程，且先于会话行删除
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(checkpointCleaner, conversations);
        order.verify(checkpointCleaner).deleteByConversationId(1L);
        order.verify(conversations).deleteById(1L);
    }

    /** 非属主/不存在统一 404（多租户 v0.2：不泄漏存在性），且不触发级联清理 */
    @Test
    void deleteUnknownConversationReturns404AndSkipsCleanup() throws Exception {
        when(conversations.findByIdAndTenantIdAndUserId(eq(404L), any(), any()))
            .thenReturn(java.util.Optional.empty());

        mockMvc.perform(delete("/api/conversations/404").headers(authHeaders))
            .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(checkpointCleaner, org.mockito.Mockito.never())
            .deleteByConversationId(org.mockito.ArgumentMatchers.anyLong());
    }
}
