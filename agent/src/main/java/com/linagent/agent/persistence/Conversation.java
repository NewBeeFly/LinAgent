package com.linagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 会话实体，对应 conversation 表（V1 DDL + V3 压缩锚点列 + V5 归属列）。
 */
@Table("conversation")
public record Conversation(@Id Long id, String title, String threadId, String compactSummary,
                           Integer compactedTurnSeq, String tenantId, String userId,
                           Instant createdAt, Instant updatedAt) {

    /** 新会话构造：未压缩（无摘要），压缩锚点 0，归属 (tenantId, userId) */
    public static Conversation create(String title, String threadId,
                                      String tenantId, String userId, Instant now) {
        return new Conversation(null, title, threadId, null, 0, tenantId, userId, now, now);
    }
}
