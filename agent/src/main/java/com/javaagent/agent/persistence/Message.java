package com.javaagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 消息实体，对应 message 表（V1 DDL）。
 */
@Table("message")
public record Message(@Id Long id, Long turnId, int seq, String msgType, String content,
                      String callId, String toolName, String arguments, String result,
                      Boolean success, Long durationMs, Instant createdAt) {

    public static Message user(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "USER", content, null, null, null, null, null, null, Instant.now());
    }

    public static Message thinking(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "THINKING", content, null, null, null, null, null, null, Instant.now());
    }

    public static Message text(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "TEXT", content, null, null, null, null, null, null, Instant.now());
    }

    public static Message toolCall(Long turnId, int seq, String callId, String toolName, String arguments) {
        return new Message(null, turnId, seq, "TOOL_CALL", null, callId, toolName, arguments, null, null, null, Instant.now());
    }

    public static Message toolResult(Long turnId, int seq, String callId, String toolName,
                                     String result, boolean success, long durationMs) {
        return new Message(null, turnId, seq, "TOOL_RESULT", null, callId, toolName, null,
            result, success, durationMs, Instant.now());
    }

    public static Message error(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "ERROR", content, null, null, null, null, null, null, Instant.now());
    }
}
