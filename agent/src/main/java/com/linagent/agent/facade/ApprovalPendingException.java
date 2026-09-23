package com.linagent.agent.facade;

/**
 * 会话存在未决审批轮（turn.status=WAITING_APPROVAL）时拒绝新消息。
 * web 层映射 409（Task 6 接）——先决议后继续，避免同一会话两个未决审批的
 * checkpoint 竞态（resume 依赖 threadId 最新 checkpoint 的 nextNodeId）。
 */
public class ApprovalPendingException extends RuntimeException {

    private final Long conversationId;

    public ApprovalPendingException(Long conversationId) {
        super("会话存在待审批的轮次，请先完成审批: " + conversationId);
        this.conversationId = conversationId;
    }

    /** 409 响应体携带的会话定位（web GlobalExceptionHandler 消费） */
    public Long conversationId() {
        return conversationId;
    }
}
