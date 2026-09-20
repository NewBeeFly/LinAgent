package com.javaagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 会话实体，对应 conversation 表（V1 DDL）。
 */
@Table("conversation")
public record Conversation(@Id Long id, String title, String threadId,
                           String compactSummary, Instant createdAt, Instant updatedAt) {
}
