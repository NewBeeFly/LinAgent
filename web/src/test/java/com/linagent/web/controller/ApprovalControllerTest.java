package com.linagent.web.controller;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.linagent.agent.approval.InMemorySessionRules;
import com.linagent.agent.facade.AgentEvent;
import com.linagent.agent.facade.AgentFacade;
import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.po.PermissionRule;
import com.linagent.agent.persistence.po.Turn;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.repository.PermissionRuleRepository;
import com.linagent.agent.persistence.repository.TurnRepository;
import com.linagent.web.auth.RequestAuthenticator;
import com.linagent.web.stream.SseEventMapper;
import com.linagent.web.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批端点协议测试（Task 6 brief Step 1）：@WebMvcTest + 全仓库/facade @MockBean
 * （ChatControllerSseTest 模式）。409 幂等、callId 匹配、remember 三档写入位置、
 * resume SSE 流形态、GET pending 从 message 表 + 引擎重算构建。
 */
@WebMvcTest(ApprovalController.class)
@AutoConfigureWebTestClient
@Import(SseEventMapper.class)
class ApprovalControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean com.linagent.agent.persistence.repository.AppUserRepository appUsers;
    @MockBean com.linagent.agent.persistence.repository.GraphThreadRepository graphThreads;
    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;
    @MockBean PermissionRuleRepository permissionRules;
    @MockBean InMemorySessionRules sessionRules;
    @MockBean RequestAuthenticator authenticator;

    private static final Long CONV_ID = 1L;
    private static final Long TURN_ID = 10L;
    /** 待决议轮固定形态：seq=1，WAITING_APPROVAL */
    private static final Turn WAITING = new Turn(TURN_ID, CONV_ID, 1, "WAITING_APPROVAL",
        null, null, Instant.now(), null);

    @BeforeEach
    void auth() {
        webTestClient = webTestClient.mutate().defaultHeaders(TestAuth.LINMJ).build();
        when(authenticator.authenticate("default", "linmj"))
            .thenReturn(Optional.of(TestAuth.LINMJ_CTX));
        when(permissionRules.findByTenantIdAndUserIdAndEffect(any(), any(), any())).thenReturn(List.of());
    }

    /** pending 轮消息行固定形态：USER(0) + 待审批 TOOL_CALL(1, shell mkdir demo) */
    private void stubPendingToolCall() {
        when(turns.findTopByConversationIdAndStatusOrderBySeqDesc(CONV_ID, "WAITING_APPROVAL"))
            .thenReturn(Optional.of(WAITING));
        when(messages.findByTurnIdOrderBySeq(TURN_ID)).thenReturn(List.of(
            new Message(1L, TURN_ID, 0, "USER", "建目录", null, null, null, null, null, null, Instant.now()),
            new Message(2L, TURN_ID, 1, "TOOL_CALL", null, "call-1", "shell",
                "{\"command\":\"mkdir demo\"}", null, null, null, Instant.now())));
    }

    private void stubResumeStream() {
        when(agentFacade.resume(eq(CONV_ID), any())).thenReturn(Flux.just(
            new AgentEvent.Meta(TURN_ID, CONV_ID, "step-3.7-flash"),
            new AgentEvent.TurnDone(TURN_ID, "STOP", new AgentEvent.Usage(1, 2, 3))));
    }

    // ── POST：决议 + resume SSE ──

    @Test
    void decideApproveOnceResumesSseStreamWithoutRuleWrites() {
        stubPendingToolCall();
        stubResumeStream();

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"approve"}],"remember":"once"}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class).value(body -> assertThat(body)
                .contains("event:meta").contains("\"turnId\":10")
                .contains("event:turn_done").contains("\"seq\":2"));

        // once：无任何 remember 写入
        verify(permissionRules, never()).save(any());
        verify(sessionRules, never()).add(any(), any(), any(), any(), any());
    }

    /** feedback 逐项映射：id/name/arguments 取自 pending 行，result/description 取自决议 */
    @Test
    void decideRejectBuildsFeedbackWithReasonAsDescription() {
        stubPendingToolCall();
        stubResumeStream();

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"reject","reason":"不要建目录"}],"remember":"once"}
                """)
            .exchange()
            .expectStatus().isOk();

        org.mockito.ArgumentCaptor<InterruptionMetadata> captor =
            org.mockito.ArgumentCaptor.forClass(InterruptionMetadata.class);
        verify(agentFacade).resume(eq(CONV_ID), captor.capture());
        InterruptionMetadata.ToolFeedback feedback = captor.getValue().toolFeedbacks().get(0);
        assertThat(feedback.getId()).isEqualTo("call-1");
        assertThat(feedback.getName()).isEqualTo("shell");
        assertThat(feedback.getArguments()).isEqualTo("{\"command\":\"mkdir demo\"}");
        assertThat(feedback.getResult()).isEqualTo(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED);
        assertThat(feedback.getDescription()).isEqualTo("不要建目录");
    }

    /** forever：suggestPattern 服务端重算（mkdir demo → "mkdir demo *"），按归属落库 */
    @Test
    void decideApproveForeverPersistsSuggestedRule() {
        stubPendingToolCall();
        stubResumeStream();
        when(permissionRules.existsByTenantIdAndUserIdAndToolNameAndPattern(
            eq("default"), eq("linmj"), eq("shell"), eq("mkdir demo *"))).thenReturn(false);

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"approve"}],"remember":"forever"}
                """)
            .exchange()
            .expectStatus().isOk();

        org.mockito.ArgumentCaptor<PermissionRule> captor =
            org.mockito.ArgumentCaptor.forClass(PermissionRule.class);
        verify(permissionRules).save(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("default");
        assertThat(captor.getValue().userId()).isEqualTo("linmj");
        assertThat(captor.getValue().toolName()).isEqualTo("shell");
        assertThat(captor.getValue().pattern()).isEqualTo("mkdir demo *");
        assertThat(captor.getValue().effect()).isEqualTo("ALLOW");
    }

    /** forever 冲突容忍（Task 2 TOCTOU 裁定）：预检命中 → 不再落库，流照常 */
    @Test
    void decideApproveForeverSkipsSaveWhenRuleAlreadyExists() {
        stubPendingToolCall();
        stubResumeStream();
        when(permissionRules.existsByTenantIdAndUserIdAndToolNameAndPattern(
            any(), any(), any(), any())).thenReturn(true);

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"approve"}],"remember":"forever"}
                """)
            .exchange()
            .expectStatus().isOk();

        verify(permissionRules, never()).save(any());
    }

    /** 预检未命中但落库撞唯一约束（并发插入）：容忍不炸，流照常 */
    @Test
    void decideApproveForeverToleratesDuplicateKeyOnSave() {
        stubPendingToolCall();
        stubResumeStream();
        when(permissionRules.existsByTenantIdAndUserIdAndToolNameAndPattern(
            any(), any(), any(), any())).thenReturn(false);
        when(permissionRules.save(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"approve"}],"remember":"forever"}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body -> assertThat(body).contains("event:turn_done"));
    }

    /** session：只进内存（会话级），不落库 */
    @Test
    void decideApproveSessionWritesMemoryOnly() {
        stubPendingToolCall();
        stubResumeStream();

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"approve"}],"remember":"session"}
                """)
            .exchange()
            .expectStatus().isOk();

        verify(sessionRules).add("default", "linmj", CONV_ID, "shell", "mkdir demo *");
        verify(permissionRules, never()).save(any());
    }

    // ── POST：409 协议 ──

    @Test
    void decideWithoutPendingTurnReturns409WithConversationId() {
        when(turns.findTopByConversationIdAndStatusOrderBySeqDesc(CONV_ID, "WAITING_APPROVAL"))
            .thenReturn(Optional.empty());

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"approve"}],"remember":"once"}
                """)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody(Map.class).value(body -> {
                assertThat(body.get("conversationId")).isEqualTo(1);
                assertThat((String) body.get("message")).contains("无待审批");
            });

        verify(agentFacade, never()).resume(any(), any());
    }

    @Test
    void decideWithMismatchedCallIdsReturns409() {
        stubPendingToolCall();

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-other","decision":"approve"}],"remember":"once"}
                """)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody(Map.class).value(body ->
                assertThat((String) body.get("message")).contains("不匹配"));

        verify(agentFacade, never()).resume(any(), any());
        verify(permissionRules, never()).save(any());
    }

    /** 终审 I1：并发双决议占轮——输者在 facade.resume 的 claimWaitingTurn 落空抛
     *  ApprovalConflictException → 确定性 409（赢者独占续跑，工具不双执行） */
    @Test
    void decideWhenConcurrentClaimLosesReturns409() {
        stubPendingToolCall();
        when(agentFacade.resume(eq(CONV_ID), any()))
            .thenThrow(new com.linagent.agent.facade.ApprovalConflictException(
                CONV_ID, "审批轮已被并发决议抢占，请刷新后重试: " + CONV_ID));

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"approve"}],"remember":"once"}
                """)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody(Map.class).value(body -> {
                assertThat(body.get("conversationId")).isEqualTo(1);
                assertThat((String) body.get("message")).contains("并发决议抢占");
            });
    }

    @Test
    void decideWithInvalidRememberOrDecisionReturns400() {
        stubPendingToolCall();

        webTestClient.post().uri("/api/conversations/1/approvals")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"items":[{"callId":"call-1","decision":"maybe"}],"remember":"always"}
                """)
            .exchange()
            .expectStatus().isBadRequest();
    }

    // ── GET：pending 详情 ──

    @Test
    void pendingReturns204WhenNoWaitingTurn() {
        when(turns.findTopByConversationIdAndStatusOrderBySeqDesc(CONV_ID, "WAITING_APPROVAL"))
            .thenReturn(Optional.empty());

        webTestClient.get().uri("/api/conversations/1/approvals")
            .exchange()
            .expectStatus().isNoContent();
    }

    /** 卡片数据从 message 表重建 + 引擎实时重算：subVerdicts 未命中明细 + suggestedRule 服务端重算 */
    @Test
    void pendingRebuildsItemsFromToolCallRowsWithEngineReeval() {
        stubPendingToolCall();

        webTestClient.get().uri("/api/conversations/1/approvals")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body -> {
                assertThat(body).contains("\"turnId\":10");
                assertThat(body).contains("\"callId\":\"call-1\"");
                assertThat(body).contains("\"toolName\":\"shell\"");
                assertThat(body).contains("\"arguments\":\"{\\\"command\\\":\\\"mkdir demo\\\"}\"");
                assertThat(body).contains("\"payload\":\"mkdir demo\"");
                assertThat(body).contains("\"suggestedRule\":\"mkdir demo *\"");
                assertThat(body).contains("\"allowed\":false");
            });
    }

    /** 已执行调用（TOOL_CALL 有配对 TOOL_RESULT）不算 pending；引擎重算 subVerdicts 实时反映
     *  user 规则（复合命令部分命中：git status 段 allowed/user，未覆盖段仍 false） */
    @Test
    void pendingExcludesExecutedCallsAndReevaluatesWithUserRules() {
        when(turns.findTopByConversationIdAndStatusOrderBySeqDesc(CONV_ID, "WAITING_APPROVAL"))
            .thenReturn(Optional.of(WAITING));
        // 白名单工具已执行（配对结果行）+ 待审批复合命令
        when(messages.findByTurnIdOrderBySeq(TURN_ID)).thenReturn(List.of(
            new Message(1L, TURN_ID, 0, "USER", "看看再建", null, null, null, null, null, null, Instant.now()),
            new Message(2L, TURN_ID, 1, "TOOL_CALL", null, "call-ls", "shell",
                "{\"command\":\"ls -la\"}", null, null, null, Instant.now()),
            new Message(3L, TURN_ID, 2, "TOOL_RESULT", null, "call-ls", "shell",
                null, "a.txt", true, 5L, Instant.now()),
            new Message(4L, TURN_ID, 3, "TOOL_CALL", null, "call-1", "shell",
                "{\"command\":\"pip install requests && rm x\"}", null, null, null, Instant.now())));
        // user 规则放行 pip install 段（非白名单命令，鉴别 user 层生效）：重算后该段
        // allowed=true、source=user，rm x 段仍待审批
        when(permissionRules.findByTenantIdAndUserIdAndEffect("default", "linmj", "ALLOW"))
            .thenReturn(List.of(PermissionRule.allow("default", "linmj", "shell", "pip install *")));

        webTestClient.get().uri("/api/conversations/1/approvals")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body -> {
                assertThat(body).doesNotContain("call-ls");
                assertThat(body).contains("\"callId\":\"call-1\"");
                assertThat(body).contains("\"segment\":\"pip install requests\"")
                    .contains("\"allowed\":true").contains("\"source\":\"user\"");
                assertThat(body).contains("\"segment\":\"rm x\"").contains("\"allowed\":false");
                // 复合命令 suggestedRule 取前两 token：pip install *
                assertThat(body).contains("\"suggestedRule\":\"pip install *\"");
            });
    }
}
