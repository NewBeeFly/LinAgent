package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.AppUser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJdbcTest(properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V4__multi_tenant.sql"
})
@Testcontainers
class AppUserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    @Autowired
    AppUserRepository users;

    @Test
    void seedUsersQueryableByCompositeKey() {
        Optional<AppUser> linmj = users.findByTenantIdAndUserId("default", "linmj");
        assertThat(linmj).hasValueSatisfying(u -> {
            assertThat(u.name()).isEqualTo("林同学");
            assertThat(u.createdAt()).isNotNull();
        });
    }

    @Test
    void unknownUserIsEmpty() {
        assertThat(users.findByTenantIdAndUserId("default", "stranger")).isEmpty();
        assertThat(users.findByTenantIdAndUserId("other", "linmj")).isEmpty();
    }
}
