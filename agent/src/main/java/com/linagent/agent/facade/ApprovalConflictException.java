package com.linagent.agent.facade;

/**
 * 审批决议与当前会话状态冲突（Task 6，web 层映射 409）：
 * 无待决议轮（重复提交/已决议/决议竞态）或提交项与 pending 调用集合不匹配。
 * 与 {@link ApprovalPendingException}（chat 被 pending 挡回）互为反向语义。
 */
public class ApprovalConflictException extends RuntimeException {

    private final Long conversationId;

    public ApprovalConflictException(Long conversationId, String message) {
        super(message);
        this.conversationId = conversationId;
    }

    public Long conversationId() {
        return conversationId;
    }
}
