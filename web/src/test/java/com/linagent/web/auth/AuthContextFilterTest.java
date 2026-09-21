package com.linagent.web.auth;

import com.linagent.agent.context.AuthContextHolder;
import com.linagent.web.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(com.linagent.web.controller.ConversationController.class)
@AutoConfigureWebTestClient
@Import(AuthContextFilter.class)
@TestMethodOrder(OrderAnnotation.class)
class AuthContextFilterTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean com.linagent.agent.persistence.repository.AppUserRepository appUsers;
    @MockBean com.linagent.agent.persistence.repository.GraphThreadRepository graphThreads;
    @MockBean RequestAuthenticator authenticator;
    @MockBean com.linagent.agent.persistence.repository.ConversationRepository conversations;
    @MockBean com.linagent.agent.persistence.repository.TurnRepository turns;
    @MockBean com.linagent.agent.persistence.repository.MessageRepository messages;
    @MockBean com.linagent.agent.persistence.support.CheckpointCleaner checkpointCleaner;
    @MockBean com.linagent.agent.facade.AgentFacade agentFacade;

    @BeforeEach
    void stub() {
        when(authenticator.authenticate("default", "linmj"))
            .thenReturn(Optional.of(TestAuth.LINMJ_CTX));
    }

    @Test
    void missingHeaderRejected401() {
        webTestClient.get().uri("/api/conversations")
            .exchange().expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.message").isEqualTo("缺少身份请求头 x-tenant-id / x-user-id");
    }

    @Test
    void unknownUserRejected401() {
        when(authenticator.authenticate(any(), any())).thenReturn(Optional.empty());
        webTestClient.get().uri("/api/conversations")
            .header("x-tenant-id", "default").header("x-user-id", "stranger")
            .exchange().expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.message").isEqualTo("未知身份: default/stranger");
    }

    @Test
    @Order(1)
    void validIdentityPassesAndContextClearedAfter() {
        when(conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc(any(), any()))
            .thenReturn(List.of());
        webTestClient.get().uri("/api/conversations")
            .header("x-tenant-id", "default").header("x-user-id", "linmj")
            .exchange().expectStatus().isOk();
        // finally 清理：请求往返结束后无残留
        assertThat(AuthContextHolder.get()).isNull();
    }

    @Test
    @Order(2)
    void nextRequestWithoutHeaderDoesNotInheritPriorIdentity() {
        // spec §5 守护：紧随一次成功请求后，无 header 的请求必须 401（防池化线程残留串号）
        missingHeaderRejected401();
    }

    @Test
    void nonApiPathBypassesAuth() {
        when(authenticator.authenticate(any(), any())).thenReturn(Optional.empty());
        webTestClient.get().uri("/index.html")
            .exchange().expectStatus().isNotFound(); // 404（无此静态资源）而非 401
    }

    @Test
    void identityOptionsPathExemptFromAuth() {
        // 切换器候选名单免鉴权：无身份头也不是 401（本切片无该 mapping → 404 落地 DispatcherServlet）
        when(authenticator.authenticate(any(), any())).thenReturn(Optional.empty());
        webTestClient.get().uri("/api/identity/options")
            .exchange().expectStatus().isNotFound();
    }
}
