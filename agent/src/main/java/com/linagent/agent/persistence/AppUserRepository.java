package com.linagent.agent.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * app_user 只读查询。复合主键 (tenant_id, user_id) 且仅查询无持久化，
 * 沿用 CheckpointCleaner 的 @Component + JdbcTemplate 先例，不走 CrudRepository。
 */
@Component
public class AppUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AppUser> findByTenantIdAndUserId(String tenantId, String userId) {
        List<AppUser> rows = jdbcTemplate.query(
            "SELECT tenant_id, user_id, name, created_at FROM app_user "
                + "WHERE tenant_id = ? AND user_id = ?",
            (rs, i) -> new AppUser(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()),
            tenantId, userId);
        return rows.stream().findFirst();
    }
}
