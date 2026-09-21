package com.linagent.web.controller;

import com.linagent.agent.persistence.ConversationAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 领域异常 → HTTP 语义。404 统一文案，不泄漏资源存在性。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConversationAccessDeniedException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> conversationAccessDenied(ConversationAccessDeniedException e) {
        return Map.of("message", e.getMessage());
    }
}
