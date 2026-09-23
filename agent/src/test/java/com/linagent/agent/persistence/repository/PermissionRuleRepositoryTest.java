package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.PermissionRule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJdbcTest(properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V6__permission_rule.sql"
})
@Testcontainers
class PermissionRuleRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    @Autowired
    PermissionRuleRepository rules;

    @Test
    void allowRuleRoundTripAndTenantIsolation() {
        PermissionRule saved = rules.save(
            PermissionRule.allow("default", "linmj", "shell", "pip install *"));
        assertThat(saved.effect()).isEqualTo("ALLOW");
        assertThat(rules.findByTenantIdAndUserIdAndEffect("default", "linmj", "ALLOW"))
            .extracting(PermissionRule::pattern).containsExactly("pip install *");
        // 跨租户/用户隔离
        assertThat(rules.findByTenantIdAndUserIdAndEffect("other", "linmj", "ALLOW")).isEmpty();
        assertThat(rules.findByTenantIdAndUserIdAndEffect("default", "someone", "ALLOW")).isEmpty();
        // 唯一约束预检
        assertThat(rules.existsByTenantIdAndUserIdAndToolNameAndPattern(
            "default", "linmj", "shell", "pip install *")).isTrue();
    }

    /** 删除按 (tenantId, userId, id) 三元收敛：非属主删除不生效，属主删除即消失 */
    @Test
    void scopedDeleteOnlyRemovesOwnedRule() {
        PermissionRule saved = rules.save(
            PermissionRule.allow("default", "linmj", "write_file", "reports/*"));

        rules.deleteByTenantIdAndUserIdAndId("default", "someone", saved.id());
        assertThat(rules.existsByTenantIdAndUserIdAndToolNameAndPattern(
            "default", "linmj", "write_file", "reports/*")).isTrue();

        rules.deleteByTenantIdAndUserIdAndId("default", "linmj", saved.id());
        assertThat(rules.existsByTenantIdAndUserIdAndToolNameAndPattern(
            "default", "linmj", "write_file", "reports/*")).isFalse();
    }
}
