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
import com.linagent.agent.persistence.support.CheckpointCleaner;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.repository.TurnRepository;
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

    @MockBean com.linagent.agent.persistence.repository.AppUserRepository appUsers;
    @MockBean com.linagent.agent.persistence.repository.GraphThreadRepository graphThreads;
    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;
    @MockBean com.linagent.agent.persistence.repository.PermissionRuleRepository permissionRules;
    @MockBean CheckpointCleaner checkpointCleaner;
    /** 会话删除级联清 session 审批规则（终审 M1）：InMemorySessionRules 为 @Component，
     *  不在 @WebMvcTest 切片内，构造器注入需要须 @MockBean */
    @MockBean com.linagent.agent.approval.InMemorySessionRules sessionRules;
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
            com.linagent.agent.persistence.po.Conversation e = inv.getArgument(0);
            if (e.id() == null) {
                return new com.linagent.agent.persistence.po.Conversation(1L, e.title(), e.threadId(),
                    e.compactSummary(), e.compactedTurnSeq(), e.tenantId(), e.userId(),
                    e.mode(), e.createdAt(), e.updatedAt());
            }
            return e;
        });

        mockMvc.perform(post("/api/conversations").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新会话\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.title").value("新会话"))
            .andExpect(jsonPath("$.mode").value("STANDARD"));
    }

    /**
     * 终审 Important 1：create 两段式落库——先 save 拿 id，再落 threadId = "conv-{id}"。
     * nanoTime 形态的 threadId 与 CheckpointCleaner 的删除模式（conv-{id} / conv-{id}-v%）
     * 匹配不上，导致未压缩会话的 checkpoint 永久孤儿。
     */
    @Test
    void createPersistsThreadIdEqualToConvIdPrefix() throws Exception {
        when(conversations.save(any())).thenAnswer(inv -> {
            com.linagent.agent.persistence.po.Conversation e = inv.getArgument(0);
            if (e.id() == null) {
                return new com.linagent.agent.persistence.po.Conversation(1L, e.title(), e.threadId(),
                    e.compactSummary(), e.compactedTurnSeq(), e.tenantId(), e.userId(),
                    e.mode(), e.createdAt(), e.updatedAt());
            }
            return e;
        });

        mockMvc.perform(post("/api/conversations").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.linagent.agent.persistence.po.Conversation> captor =
            org.mockito.ArgumentCaptor.forClass(com.linagent.agent.persistence.po.Conversation.class);
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
            new com.linagent.agent.persistence.po.Conversation(1L, "会话A", "conv-1", null, 0, "default", "linmj",
                "STANDARD", java.time.Instant.now(), java.time.Instant.now())));
        when(turns.countByConversationId(1L)).thenReturn(3);

        mockMvc.perform(get("/api/conversations").headers(authHeaders))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].turnCount").value(3))
            .andExpect(jsonPath("$[0].mode").value("STANDARD"));
    }

    @Test
    void turnsReplayReturnsNestedMessages() throws Exception {
        // requireOwned 为接口 default 方法，Mockito mock 下是 no-op——预检直接放行，无需打桩
        when(turns.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of(
            new com.linagent.agent.persistence.po.Turn(10L, 1L, 1, "COMPLETED", "STOP", null,
                java.time.Instant.now(), java.time.Instant.now())));
        when(messages.findByTurnIdOrderBySeq(10L)).thenReturn(List.of(
            new com.linagent.agent.persistence.po.Message(1L, 10L, 0, "USER", "问题", null, null, null, null, null, null, java.time.Instant.now()),
            new com.linagent.agent.persistence.po.Message(2L, 10L, 1, "TEXT", "回答", null, null, null, null, null, null, java.time.Instant.now())));

        mockMvc.perform(get("/api/conversations/1/turns").headers(authHeaders))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].messages[0].msgType").value("USER"))
            .andExpect(jsonPath("$[0].messages[1].msgType").value("TEXT"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/conversations/1").headers(authHeaders))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteCascadesCheckpointCleanupThenRemovesConversation() throws Exception {
        mockMvc.perform(delete("/api/conversations/1").headers(authHeaders))
            .andExpect(status().isNoContent());

        // checkpoint 级联清理（Errata 3）：按会话 id 清 conv-1 / conv-1-v* 线程，且先于会话行删除；
        // session 审批规则同步清出内存（终审 M1，同随会话删除收尾）
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(checkpointCleaner, sessionRules, conversations);
        order.verify(checkpointCleaner).deleteByConversationId(1L);
        order.verify(sessionRules).evict(1L);
        order.verify(conversations).deleteById(1L);
    }

    /** 非属主/不存在统一 404（多租户 v0.2：不泄漏存在性），且不触发级联清理 */
    @Test
    void deleteUnknownConversationReturns404AndSkipsCleanup() throws Exception {
        // default 方法在 mock 上不执行真实实现，归属预检的未命中路径需直接对 requireOwned 抛异常
        org.mockito.Mockito.doThrow(
                new com.linagent.agent.persistence.repository.ConversationAccessDeniedException(404L))
            .when(conversations).requireOwned(eq(404L), any(), any());

        mockMvc.perform(delete("/api/conversations/404").headers(authHeaders))
            .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(checkpointCleaner, org.mockito.Mockito.never())
            .deleteByConversationId(org.mockito.ArgumentMatchers.anyLong());
    }

    // ── PUT /mode（modes Task 3）：三档切换 + 严格校验 + 归属/pending 防线 ──

    /** 已落库会话实体（固定旧时间戳，用于断言 updatedAt 刷新与 createdAt 原样转发） */
    private static com.linagent.agent.persistence.po.Conversation conv(Long id, String mode) {
        java.time.Instant past = java.time.Instant.parse("2026-09-01T00:00:00Z");
        return new com.linagent.agent.persistence.po.Conversation(id, "会话A", "conv-" + id, null, 0,
            "default", "linmj", mode, past, past);
    }

    /** 三档合法值（含大小写/空白归一）落库且响应 {id, mode}；其余字段原样转发、updatedAt 刷新 */
    @Test
    void updateModeAcceptsAllThreeModesAndPersists() throws Exception {
        when(conversations.findById(1L)).thenReturn(Optional.of(conv(1L, "STANDARD")));

        for (String raw : new String[] {"AUTO", " chat ", "standard"}) {
            mockMvc.perform(put("/api/conversations/1/mode").headers(authHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"mode\":\"%s\"}".formatted(raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.mode").value(raw.trim().toUpperCase()));
        }

        org.mockito.ArgumentCaptor<com.linagent.agent.persistence.po.Conversation> captor =
            org.mockito.ArgumentCaptor.forClass(com.linagent.agent.persistence.po.Conversation.class);
        verify(conversations, times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(com.linagent.agent.persistence.po.Conversation::mode)
            .containsExactly("AUTO", "CHAT", "STANDARD");
        // 非目标字段原样转发（title/threadId/归属/createdAt），仅 mode 与 updatedAt 变化
        com.linagent.agent.persistence.po.Conversation saved = captor.getAllValues().get(0);
        assertThat(saved.title()).isEqualTo("会话A");
        assertThat(saved.threadId()).isEqualTo("conv-1");
        assertThat(saved.tenantId()).isEqualTo("default");
        assertThat(saved.userId()).isEqualTo("linmj");
        assertThat(saved.createdAt()).isEqualTo(java.time.Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(saved.updatedAt()).isAfter(java.time.Instant.parse("2026-09-01T00:00:00Z"));
    }

    /** 严格校验：本端点不静默回落 STANDARD——非法/空白/缺失值一律 400（message 供前端提示），不落库 */
    @Test
    void updateModeRejectsInvalidValueWith400() throws Exception {
        for (String body : new String[] {"{\"mode\":\"TURBO\"}", "{\"mode\":\"\"}", "{}"}) {
            mockMvc.perform(put("/api/conversations/1/mode").headers(authHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        }
        // 完全空 body（@RequestBody required=false → null）同样 400
        mockMvc.perform(put("/api/conversations/1/mode").headers(authHeaders))
            .andExpect(status().isBadRequest());

        verify(conversations, org.mockito.Mockito.never()).save(any());
    }

    /** 归属 404 统一语义（不泄漏存在性），且不触 pending 检查与落库 */
    @Test
    void updateModeUnknownConversationReturns404() throws Exception {
        org.mockito.Mockito.doThrow(
                new com.linagent.agent.persistence.repository.ConversationAccessDeniedException(404L))
            .when(conversations).requireOwned(eq(404L), any(), any());

        mockMvc.perform(put("/api/conversations/404/mode").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"AUTO\"}"))
            .andExpect(status().isNotFound());

        verify(turns, org.mockito.Mockito.never())
            .existsByConversationIdAndStatus(org.mockito.ArgumentMatchers.anyLong(), any());
        verify(conversations, org.mockito.Mockito.never()).save(any());
    }

    /** 待审批轮挡回切档（409 携 conversationId，前端复用审批卡片链路），不落库 */
    @Test
    void updateModeBlockedByPendingApprovalReturns409() throws Exception {
        when(turns.existsByConversationIdAndStatus(1L, "WAITING_APPROVAL")).thenReturn(true);

        mockMvc.perform(put("/api/conversations/1/mode").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"AUTO\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.conversationId").value(1));

        verify(conversations, org.mockito.Mockito.never()).save(any());
    }
}
