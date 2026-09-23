package com.linagent.agent.persistence.po;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 审批规则实体，对应 permission_rule 表（V6 DDL）。
 * forever 作用域的「永久记住我的选择」规则；session 作用域规则在内存，不落这张表。
 */
@Table("permission_rule")
public record PermissionRule(@Id Long id, String tenantId, String userId,
                             String toolName, String pattern, String effect,
                             Instant createdAt) {

    /** 新增放行规则：effect 默认 'ALLOW' 由应用层保证（MVP 仅 ALLOW，deny/ask 预留二期） */
    public static PermissionRule allow(String tenantId, String userId, String toolName, String pattern) {
        return new PermissionRule(null, tenantId, userId, toolName, pattern, "ALLOW", Instant.now());
    }
}
