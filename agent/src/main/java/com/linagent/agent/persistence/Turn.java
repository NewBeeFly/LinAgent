package com.linagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 轮次实体，对应 turn 表（V1 DDL）。
 */
@Table("turn")
public record Turn(@Id Long id, Long conversationId, int seq, String status,
                   String finishReason, String usage, Instant startedAt, Instant finishedAt) {

    public static Turn running(Long conversationId, int seq) {
        return new Turn(null, conversationId, seq, "RUNNING", null, null, Instant.now(), null);
    }

    public Turn complete(String finishReason, String usageJson) {
        return new Turn(id, conversationId, seq, "COMPLETED", finishReason, usageJson, startedAt, Instant.now());
    }

    public Turn fail(String reason) {
        return new Turn(id, conversationId, seq, "FAILED", reason, usage, startedAt, Instant.now());
    }
}
