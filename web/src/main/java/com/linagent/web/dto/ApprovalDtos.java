package com.linagent.web.dto;

import com.linagent.agent.approval.PermissionRuleEngine.PendingItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 审批端点请求/响应（Task 6，spec §6）。
 * PendingItem 复用 agent 引擎产出类型——与 ApprovalRequest 事件/前端卡片同构。
 */
public final class ApprovalDtos {

    private ApprovalDtos() {
    }

    /** POST /api/conversations/{id}/approvals 请求体：整批提交（SAA validateFeedback 硬约束） */
    public record ApprovalDecisionRequest(
            @NotEmpty @Valid List<ItemDecision> items,
            @NotBlank @Pattern(regexp = "once|session|forever") String remember) {
    }

    /** 单项决议：callId 必须与 pending TOOL_CALL 行逐一匹配（不匹配 → 409） */
    public record ItemDecision(
            @NotBlank String callId,
            @NotBlank @Pattern(regexp = "approve|reject") String decision,
            String reason) {
    }

    /** GET /api/conversations/{id}/approvals 响应：从 pending TOOL_CALL 行 + 引擎重算构建 */
    public record PendingApprovalResponse(Long turnId, List<PendingItem> items) {
    }
}
