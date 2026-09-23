package com.linagent.agent.persistence.po;

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

    /** 审批中断态（Task 5）：turn 停在等待决议，finishedAt 置空（未终态）；决议后 resume 补终态 */
    public Turn waitingApproval() {
        return new Turn(id, conversationId, seq, "WAITING_APPROVAL", null, usage, startedAt, null);
    }

    /** 决议续跑（Task 6 resume）：WAITING_APPROVAL → RUNNING，finish/finishedAt 清空（终态由续跑管线补） */
    public Turn resumed() {
        return new Turn(id, conversationId, seq, "RUNNING", null, usage, startedAt, null);
    }

    public Turn fail(String reason) {
        return new Turn(id, conversationId, seq, "FAILED", reason, usage, startedAt, Instant.now());
    }
}
