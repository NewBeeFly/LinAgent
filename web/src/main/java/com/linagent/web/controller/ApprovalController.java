package com.linagent.web.controller;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import com.linagent.agent.approval.ApprovalHook;
import com.linagent.agent.approval.InMemorySessionRules;
import com.linagent.agent.approval.PermissionRuleEngine;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.facade.AgentFacade;
import com.linagent.agent.facade.ApprovalConflictException;
import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.po.PermissionRule;
import com.linagent.agent.persistence.po.Turn;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.repository.PermissionRuleRepository;
import com.linagent.agent.persistence.repository.TurnRepository;
import com.linagent.web.dto.ApprovalDtos.ApprovalDecisionRequest;
import com.linagent.web.dto.ApprovalDtos.ItemDecision;
import com.linagent.web.dto.ApprovalDtos.PendingApprovalResponse;
import com.linagent.web.stream.SseEventMapper;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 审批决议与 pending 详情（Task 6，spec §6/§7 刷新恢复链）。
 *
 * <p>POST：整批决议（callId 集合与 pending 逐一匹配，不匹配 409）→ remember 三档写入
 * （forever 落 permission_rule / session 进内存 / once 无痕）→ 构建 InterruptionMetadata →
 * facade.resume 续流，响应为与 chat 同款的新 SSE 流（前端按 turnId 合并进原 turn）。
 * GET：从 message 表 pending TOOL_CALL 行重建审批卡片数据，判定明细实时重跑规则引擎
 * （中断后新增的规则下轮判定即生效，卡片上看得见）。
 */
@RestController
public class ApprovalController {

    private final AgentFacade agentFacade;
    private final SseEventMapper sseEventMapper;
    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final PermissionRuleRepository permissionRules;
    private final InMemorySessionRules sessionRules;

    public ApprovalController(AgentFacade agentFacade, SseEventMapper sseEventMapper,
                              ConversationRepository conversations, TurnRepository turns,
                              MessageRepository messages, PermissionRuleRepository permissionRules,
                              InMemorySessionRules sessionRules) {
        this.agentFacade = agentFacade;
        this.sseEventMapper = sseEventMapper;
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.permissionRules = permissionRules;
        this.sessionRules = sessionRules;
    }

    @PostMapping(value = "/api/conversations/{conversationId}/approvals",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> decide(@PathVariable Long conversationId,
                                                @Valid @RequestBody ApprovalDecisionRequest request) {
        AuthContext ctx = AuthContextHolder.require();
        conversations.requireOwned(conversationId, ctx.tenantId(), ctx.userId());
        Turn pending = requirePendingTurn(conversationId);
        List<Message> pendingCalls = pendingToolCalls(pending.id());
        requireCallIdsMatch(conversationId, pendingCalls, request.items());

        Map<String, Message> byCallId = pendingCalls.stream()
            .collect(Collectors.toMap(Message::callId, Function.identity()));
        rememberRules(ctx, conversationId, request, byCallId);

        InterruptionMetadata.Builder feedback = InterruptionMetadata.builder(ApprovalHook.HITL_NODE_FULL_NAME, null);
        for (ItemDecision item : request.items()) {
            Message row = byCallId.get(item.callId());
            FeedbackResult result = "approve".equals(item.decision()) ? FeedbackResult.APPROVED : FeedbackResult.REJECTED;
            // description 双语义：REJECTED 时为回传模型的拒绝理由；APPROVED 时忽略
            feedback.addToolFeedback(ToolFeedback.builder()
                .id(row.callId()).name(row.toolName())
                .arguments(row.arguments())
                .result(result).description(item.reason())
                .build());
        }
        return sseEventMapper.toSse(agentFacade.resume(conversationId, feedback.build()));
    }

    @GetMapping("/api/conversations/{conversationId}/approvals")
    public ResponseEntity<PendingApprovalResponse> pending(@PathVariable Long conversationId) {
        AuthContext ctx = AuthContextHolder.require();
        conversations.requireOwned(conversationId, ctx.tenantId(), ctx.userId());
        return turns.findTopByConversationIdAndStatusOrderBySeqDesc(conversationId, "WAITING_APPROVAL")
            .<ResponseEntity<PendingApprovalResponse>>map(turn -> ResponseEntity.ok(
                new PendingApprovalResponse(turn.id(), rebuildItems(ctx, conversationId, turn.id()))))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 待决议轮定位：缺失 = 重复提交/已决议 → 409（幂等保护，spec §6） */
    private Turn requirePendingTurn(Long conversationId) {
        return turns.findTopByConversationIdAndStatusOrderBySeqDesc(conversationId, "WAITING_APPROVAL")
            .orElseThrow(() -> new ApprovalConflictException(
                conversationId, "会话无待审批轮次（重复提交或已决议）: " + conversationId));
    }

    /** callId 集合与 pending 逐一匹配：SAA validateFeedback 硬约束（整批提交），错配即 409 */
    private static void requireCallIdsMatch(Long conversationId, List<Message> pendingCalls,
                                            List<ItemDecision> items) {
        Set<String> pendingIds = pendingCalls.stream().map(Message::callId).collect(Collectors.toSet());
        Set<String> requestedIds = items.stream().map(ItemDecision::callId).collect(Collectors.toSet());
        if (!pendingIds.equals(requestedIds)) {
            throw new ApprovalConflictException(conversationId,
                "审批项与待审批调用不匹配：pending=" + pendingIds + "，请求=" + requestedIds);
        }
    }

    /**
     * pending 调用 = 该轮 TOOL_CALL 行中尚无同 callId TOOL_RESULT 者（白名单工具中断前已执行
     * 的调用有配对结果行，天然排除）。
     */
    private List<Message> pendingToolCalls(Long turnId) {
        List<Message> rows = messages.findByTurnIdOrderBySeq(turnId);
        Set<String> executedIds = rows.stream()
            .filter(m -> "TOOL_RESULT".equals(m.msgType()))
            .map(Message::callId)
            .collect(Collectors.toSet());
        return rows.stream()
            .filter(m -> "TOOL_CALL".equals(m.msgType()))
            .filter(m -> !executedIds.contains(m.callId()))
            .toList();
    }

    /**
     * remember 三档写入（仅 approve 项）：once 无痕 / session 内存 / forever 落库。
     * suggestedRule 服务端重算（前端不传 pattern，spec §5 裁定）；forever 落库 conflict-safe
     * ——existsBy 预检 + 唯一约束兜底（Task 2 评审 TOCTOU 裁定），不依赖预检单独成立。
     */
    private void rememberRules(AuthContext ctx, Long conversationId, ApprovalDecisionRequest request,
                               Map<String, Message> byCallId) {
        if ("once".equals(request.remember())) {
            return;
        }
        for (ItemDecision item : request.items()) {
            if (!"approve".equals(item.decision())) {
                continue;
            }
            Message row = byCallId.get(item.callId());
            String pattern = suggestedPattern(row);
            if ("forever".equals(request.remember())) {
                rememberForever(ctx, row.toolName(), pattern);
            }
            else {
                sessionRules.add(ctx.tenantId(), ctx.userId(), conversationId, row.toolName(), pattern);
            }
        }
    }

    private void rememberForever(AuthContext ctx, String toolName, String pattern) {
        if (permissionRules.existsByTenantIdAndUserIdAndToolNameAndPattern(
                ctx.tenantId(), ctx.userId(), toolName, pattern)) {
            return;
        }
        try {
            permissionRules.save(PermissionRule.allow(ctx.tenantId(), ctx.userId(), toolName, pattern));
        }
        catch (DataIntegrityViolationException e) {
            // TOCTOU：并发插入撞唯一约束 = 已存在，容忍（幂等）
        }
    }

    /** GET pending 卡片数据：payload/suggestedRule 服务端重算，subVerdicts 实时重跑引擎 */
    private List<PermissionRuleEngine.PendingItem> rebuildItems(AuthContext ctx, Long conversationId, Long turnId) {
        PermissionRuleEngine engine = new PermissionRuleEngine(
            permissionRules.findByTenantIdAndUserIdAndEffect(ctx.tenantId(), ctx.userId(), "ALLOW"),
            sessionRules);
        PermissionRuleEngine.ApprovalContext approvalCtx =
            new PermissionRuleEngine.ApprovalContext(ctx.tenantId(), ctx.userId(), conversationId);

        List<PermissionRuleEngine.PendingItem> items = new ArrayList<>();
        for (Message row : pendingToolCalls(turnId)) {
            String payload = ApprovalHook.extractPayload(row.toolName(), row.arguments());
            PermissionRuleEngine.Verdict verdict =
                engine.evaluate(row.toolName(), row.callId(), payload, approvalCtx);
            // arguments 保持模型原始 JSON（卡片/回放数据源），与 ApprovalRequest 事件同构；
            // 中断后新增规则致全部命中时 subVerdicts 为空集（卡片仍展示，决议照常）
            List<PermissionRuleEngine.SubVerdict> subVerdicts = verdict.items().isEmpty()
                ? List.of() : verdict.items().get(0).subVerdicts();
            items.add(new PermissionRuleEngine.PendingItem(
                row.callId(), row.toolName(), row.arguments(), payload,
                subVerdicts, suggestedPattern(row)));
        }
        return items;
    }

    private static String suggestedPattern(Message row) {
        return PermissionRuleEngine.suggestPattern(
            row.toolName(), ApprovalHook.extractPayload(row.toolName(), row.arguments()));
    }
}
