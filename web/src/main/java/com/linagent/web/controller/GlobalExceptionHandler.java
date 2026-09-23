package com.linagent.web.controller;

import com.linagent.agent.facade.ApprovalConflictException;
import com.linagent.agent.facade.ApprovalPendingException;
import com.linagent.agent.persistence.repository.ConversationAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 领域异常 → HTTP 语义。404 统一文案，不泄漏资源存在性。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConversationAccessDeniedException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> conversationAccessDenied(ConversationAccessDeniedException e) {
        return Map.of("message", e.getMessage());
    }

    /**
     * 审批 409 双语义（Task 6，spec §6 协议分流）：
     * pending 存在挡回新消息 / 决议与 pending 状态冲突（重复提交、callId 不匹配、竞态）。
     * 响应体携带会话定位，前端据此拉 GET /approvals 渲染卡片。
     */
    @ExceptionHandler({ApprovalPendingException.class, ApprovalConflictException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> approvalConflict(RuntimeException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        if (e instanceof ApprovalPendingException pending) {
            body.put("conversationId", pending.conversationId());
        }
        else if (e instanceof ApprovalConflictException conflict) {
            body.put("conversationId", conflict.conversationId());
        }
        return body;
    }
}
