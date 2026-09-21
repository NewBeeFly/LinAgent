package com.linagent.agent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// V4 会 ALTER TABLE conversation，故需先加载 V1 建立 conversation 表（与 RepositoryTest 同一先例）
@DataJdbcTest(properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__init.sql,classpath:db/migration/V4__multi_tenant.sql"
})
@Import(AppUserRepository.class)
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
