package com.linagent.agent.persistence;

import java.time.Instant;

/** 用户实体（app_user 表）。只读查询，无写入路径（新增用户手工 SQL）。 */
public record AppUser(String tenantId, String userId, String name, Instant createdAt) {
}
