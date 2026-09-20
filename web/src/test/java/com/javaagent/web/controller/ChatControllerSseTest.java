package com.javaagent.web.controller;

import com.javaagent.agent.facade.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import com.javaagent.agent.facade.AgentFacade;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.TurnRepository;
import com.javaagent.web.stream.SseEventMapper;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 无 DB 也能起：@WebMvcTest + WebTestClient 绑定 MockMvc（brief 注释的推荐方式，
 * 替代需要 DataSource 的 @SpringBootTest）。SseEventMapper 为普通 @Component，
 * slice 不扫描，@Import 引入真实实现以验证事件协议映射。
 */
@WebMvcTest(ChatController.class)
@AutoConfigureWebTestClient
@Import(SseEventMapper.class)
class ChatControllerSseTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;

    @Test
    void chatStreamsSseEventsInOrder() {
        when(agentFacade.chat(eq(1L), eq("你好"))).thenReturn(Flux.just(
            new AgentEvent.Meta(10L, 1L, "step-3.7-flash"),
            new AgentEvent.ThinkingDelta(10L, "思考"),
            new AgentEvent.MessageDelta(10L, "回"),
            new AgentEvent.ToolCall(10L, "c1", "list_dir", "{\"path\":\".\"}"),
            new AgentEvent.ToolResult(10L, "c1", "list_dir", "a.txt", 120L, true),
            new AgentEvent.MessageDelta(10L, "答"),
            new AgentEvent.TurnDone(10L, "STOP", new AgentEvent.Usage(10, 5, 15))));

        webTestClient.post().uri("/api/conversations/1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new com.javaagent.web.dto.ChatRequest("你好"))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class).value(body -> {
                // 断言事件名顺序与关键字段
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("event:meta").contains("\"turnId\":10")
                    .contains("event:thinking_delta").contains("思考")
                    .contains("event:message_delta").contains("回")
                    .contains("event:tool_call").contains("list_dir")
                    .contains("event:tool_result").contains("\"success\":true")
                    .contains("event:turn_done").contains("\"totalTokens\":15");
                // seq 递增：7 个事件，末事件 seq=7
                org.assertj.core.api.Assertions.assertThat(body).contains("\"seq\":7");
                // 事件名顺序（SSE 聚合文本中的相对位置）
                org.assertj.core.api.Assertions.assertThat(body.indexOf("event:meta"))
                    .isLessThan(body.indexOf("event:thinking_delta"));
                org.assertj.core.api.Assertions.assertThat(body.indexOf("event:thinking_delta"))
                    .isLessThan(body.indexOf("event:message_delta"));
                org.assertj.core.api.Assertions.assertThat(body.indexOf("event:tool_call"))
                    .isLessThan(body.indexOf("event:tool_result"));
                org.assertj.core.api.Assertions.assertThat(body.indexOf("event:tool_result"))
                    .isLessThan(body.indexOf("event:turn_done"));
            });
    }

    @Test
    void blankContentIsRejected() {
        webTestClient.post().uri("/api/conversations/1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new com.javaagent.web.dto.ChatRequest(" "))
            .exchange()
            .expectStatus().isBadRequest();
    }
}
