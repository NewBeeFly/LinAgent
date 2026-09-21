package com.linagent.web.controller;

import com.linagent.web.support.TestAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 归属隔离（真实 DB：Testcontainers PG + Flyway V1-V5 + 真实鉴权链）：
 * tester 对 linmj 会话——列表不可见、turns 回放 404、删除 404（统一不泄漏存在性）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationIsolationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    @Autowired
    WebTestClient webTestClient;

    Long linmjConvId;

    @BeforeEach
    void setup() {
        Map<?, ?> created = webTestClient.mutate().defaultHeaders(TestAuth.LINMJ).build()
            .post().uri("/api/conversations")
            .header("Content-Type", "application/json").bodyValue(Map.of("title", "linmj的会话"))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        linmjConvId = ((Number) created.get("id")).longValue();
    }

    @AfterEach
    void cleanup() {
        webTestClient.mutate().defaultHeaders(TestAuth.LINMJ).build()
            .delete().uri("/api/conversations/%d".formatted(linmjConvId))
            .exchange().expectStatus().isNoContent();
    }

    @Test
    void testerCannotSeeLinmjConversations() {
        WebTestClient tester = webTestClient.mutate().defaultHeaders(TestAuth.TESTER).build();

        List<Map<String, Object>> list = tester.get().uri("/api/conversations")
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();
        assertThat(list).noneMatch(c -> ((Number) c.get("id")).longValue() == linmjConvId);

        tester.get().uri("/api/conversations/%d/turns".formatted(linmjConvId))
            .exchange().expectStatus().isNotFound();

        tester.delete().uri("/api/conversations/%d".formatted(linmjConvId))
            .exchange().expectStatus().isNotFound();

        // chat 404 断言依赖 Task 6 的 facade 归属预检，本任务先整块注释，Task 6 Step 5 解开：
        // tester.post().uri("/api/conversations/%d/chat".formatted(linmjConvId))
        //     .header("Content-Type", "application/json").bodyValue(Map.of("content", "hi"))
        //     .exchange().expectStatus().isNotFound();
    }
}
