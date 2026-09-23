package com.linagent.agent.facade;

import com.linagent.agent.approval.PermissionRuleEngine;

import java.util.List;

/**
 * agent 引擎对外统一事件流（框架无关，web 层转 SSE）。
 */
public sealed interface AgentEvent {

    Long turnId();

    record Meta(Long turnId, Long conversationId, String model) implements AgentEvent {}

    record ThinkingDelta(Long turnId, String content) implements AgentEvent {}

    record MessageDelta(Long turnId, String content) implements AgentEvent {}

    record ToolCall(Long turnId, String callId, String toolName, String arguments) implements AgentEvent {}

    record ToolResult(Long turnId, String callId, String toolName, String result,
                      long durationMs, boolean success) implements AgentEvent {}

    /**
     * 审批中断请求（Task 5）：流因审批 HITL 中断，本事件为流尾事件（其后无 TurnDone）。
     * items 为全部待审批项（record 字段 JSON 序列化天然可回放），决议经 Task 6 端点 resume。
     */
    record ApprovalRequest(Long turnId, Long conversationId,
                           List<PermissionRuleEngine.PendingItem> items) implements AgentEvent {}

    record TurnDone(Long turnId, String finishReason, Usage usage) implements AgentEvent {}

    record TurnError(Long turnId, String code, String message) implements AgentEvent {}

    record Usage(long promptTokens, long completionTokens, long totalTokens) {}
}
