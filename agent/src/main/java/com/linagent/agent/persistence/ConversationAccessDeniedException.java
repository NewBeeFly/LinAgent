package com.linagent.agent.persistence;

/** 会话不存在或不属于当前身份（web 层映射 404，不泄漏存在性） */
public class ConversationAccessDeniedException extends RuntimeException {

    public ConversationAccessDeniedException(Long conversationId) {
        super("会话不存在或无权访问: " + conversationId);
    }
}
