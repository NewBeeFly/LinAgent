package com.linagent.web.e2e;

import com.linagent.agent.compaction.CompactionService;
import com.linagent.web.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 端到端：真实全链路（SSE 接口 → AgentFacade → ReactAgent/工具/checkpoint → PG 落库 →
 * 回放接口），仅两个刻意替换项：
 * 1. ChatModel 换脚本桩（@Primary）——不依赖外部 LLM API，ReAct 交互形态不变
 *    （第 1 轮返回 list_dir 工具调用，之后每轮返回文本，工具循环多跑一轮也能收敛）；
 * 2. CompactionService 桩为 empty——避免 E2E 触发 LLM 压缩（脚本桩无摘要语义）。
 *
 * 其余全部真实：Testcontainers PG（Flyway V1/V2/V3 + PostgresSaver checkpoint）、
 * SegmentBuffer 落库、SseEventMapper 事件协议。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class AgentEndToEndTest {

    /**
     * @ServiceConnection 生成的 JDBC url 不带 stringtype=unspecified（工程模板 url 有、
     * 这里被 ConnectionDetails 覆盖），Turn.usage/message.arguments 的 String 直写 JSONB
     * 列会报类型不匹配——withUrlParam 把该参数补回容器 url。
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    /** 独立工作区（静态初始化先于 @DynamicPropertySource）：list_dir 有真实内容可列。
     *  Task 6 布局：FileTools 根 = {基根}/{tenant}/users/{user}，身份取 TestAuth.LINMJ */
    static final Path workspaceRoot = createWorkspace();

    static Path createWorkspace() {
        try {
            Path dir = Files.createTempDirectory("e2e-workspace");
            Path personal = dir.resolve("default").resolve("users").resolve("linmj");
            Files.createDirectories(personal);
            Files.writeString(personal.resolve("a.txt"), "e2e sample file");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void agentProps(DynamicPropertyRegistry registry) {
        registry.add("agent.workspace-root", () -> workspaceRoot.toString());
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CompactionService compactionService;

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        org.springframework.ai.chat.model.ChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @BeforeEach
    void setUp() {
        // 压缩检查放行：E2E 会话历史为空，不会触发压缩（防御脚本模型被误用于摘要）
        when(compactionService.compactIfNeeded(any())).thenReturn(Optional.empty());
        // Testcontainers 首轮（含 Flyway/JIT）可能超过默认 5s；
        // 鉴权链（Task 3）：全上下文真实 HeaderUserAuthenticator + V4 种子（default/linmj），补默认身份 header
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(60))
            .defaultHeaders(TestAuth.LINMJ).build();
    }

    /**
     * 脚本：第 1 次调用返回 list_dir 工具调用；此后每次返回最终文本
     * （ReAct 工具循环若多跑一轮，脚本仍收敛，验收断言不变）。
     */
    static class ScriptedChatModel implements org.springframework.ai.chat.model.ChatModel {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            if (calls.incrementAndGet() == 1) {
                var toolCall = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    "call-1", "function", "list_dir", "{\"path\":\".\"}");
                var assistant = org.springframework.ai.chat.messages.AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build();
                return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(assistant)));
            }
            var finalMsg = org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content("工作区里有 1 个文件")
                .build();
            return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(finalMsg)));
        }

        @Override
        public Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    @Test
    void fullTurnWithToolCallPersistsAndStreams() {
        // 1. 创建会话
        Map<?, ?> created = webTestClient.post().uri("/api/conversations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "E2E"))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        Long convId = ((Number) created.get("id")).longValue();

        // 2. 发起对话（SSE）：工具调用 → 工具结果 → 正文 → turn_done 全链路
        String body = webTestClient.post().uri("/api/conversations/%d/chat".formatted(convId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("content", "列出工作区文件"))
            .exchange().expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class).returnResult().getResponseBody();

        // 断言事件链：工具调用 → 工具结果 → turn_done（工具真实执行，结果非空）
        assertThat(body)
            .contains("event:tool_call").contains("list_dir")
            .contains("event:tool_result").contains("a.txt")
            .contains("工作区里有 1 个文件")
            .contains("event:turn_done");

        // 3. 断言 PG 完整落库：USER + TOOL_CALL + TOOL_RESULT + TEXT
        Integer messageCount = jdbcTemplate.queryForObject(
            "select count(*) from message where msg_type in ('USER','TOOL_CALL','TOOL_RESULT','TEXT')",
            Integer.class);
        assertThat(messageCount).isGreaterThanOrEqualTo(4);
        Integer toolResultRows = jdbcTemplate.queryForObject(
            "select count(*) from message where msg_type = 'TOOL_RESULT' and result like '%a.txt%'",
            Integer.class);
        assertThat(toolResultRows).isGreaterThanOrEqualTo(1);

        // 4. 回放接口包含全部消息类型（重启恢复的展示数据源）
        webTestClient.get().uri("/api/conversations/%d/turns".formatted(convId))
            .exchange().expectStatus().isOk()
            .expectBody(String.class)
            .value(b -> assertThat(b)
                .contains("USER").contains("TOOL_CALL").contains("TOOL_RESULT").contains("TEXT"));
    }
}
