package com.linagent.web.controller;

import com.linagent.agent.persistence.po.AppUser;
import com.linagent.agent.persistence.repository.AppUserRepository;
import com.linagent.web.auth.RequestAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;

/**
 * 身份候选名单：免鉴权开放端点（AuthContextFilter 豁免 /api/identity/options）——
 * 切换器需要在任何身份生效前拿到候选；本地工具取舍，登录体系上线时收紧。
 */
@WebMvcTest(IdentityController.class)
@AutoConfigureWebTestClient
class IdentityControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean com.linagent.agent.persistence.repository.GraphThreadRepository graphThreads;
    @MockBean AppUserRepository users;
    /** AuthContextFilter 为 Filter Bean 随切片装配，其依赖需补桩（豁免路径不会调用它） */
    @MockBean RequestAuthenticator authenticator;
    /** @EnableJdbcRepositories 直注在应用类上，切片仍创建仓储 Bean——与兄弟切片测试一致全部打桩 */
    @MockBean com.linagent.agent.persistence.repository.ConversationRepository conversations;
    @MockBean com.linagent.agent.persistence.repository.TurnRepository turns;
    @MockBean com.linagent.agent.persistence.repository.MessageRepository messages;
    @MockBean com.linagent.agent.persistence.support.CheckpointCleaner checkpointCleaner;
    @MockBean com.linagent.agent.facade.AgentFacade agentFacade;

    @Test
    void optionsOpenWithoutIdentityHeadersAndGroupedByTenant() {
        when(users.findAll()).thenReturn(List.of(
            new AppUser("default", "linmj", "林同学", Instant.now()),
            new AppUser("acme", "alice", "爱丽丝", Instant.now()),
            new AppUser("default", "tester", "测试", Instant.now())));

        webTestClient.get().uri("/api/identity/options")
            .exchange().expectStatus().isOk() // 不带身份头仍 200（豁免生效）
            .expectBody()
            .jsonPath("$.length()").isEqualTo(2)
            .jsonPath("$[0].tenantId").isEqualTo("default")
            .jsonPath("$[0].users.length()").isEqualTo(2)
            .jsonPath("$[0].users[0].userId").isEqualTo("linmj")
            .jsonPath("$[0].users[0].name").isEqualTo("林同学")
            .jsonPath("$[1].tenantId").isEqualTo("acme")
            .jsonPath("$[1].users[0].userId").isEqualTo("alice");
    }

    @Test
    void emptyTableYieldsEmptyArray() {
        when(users.findAll()).thenReturn(List.of());
        webTestClient.get().uri("/api/identity/options")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.length()").isEqualTo(0);
    }
}
