package com.javaagent.web.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.agent.facade.AgentEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AgentEvent → SSE（事件名 + JSON data）。seq 由流内递增序号承担。
 */
@Component
public class SseEventMapper {

    private final ObjectMapper objectMapper;

    public SseEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> toSse(Flux<AgentEvent> events) {
        AtomicLong seq = new AtomicLong();
        return events.map(evt -> toSse(evt, seq.incrementAndGet()));
    }

    ServerSentEvent<String> toSse(AgentEvent evt, long seq) {
        return ServerSentEvent.builder(toJson(payload(evt, seq)))
            .event(eventName(evt))
            .build();
    }

    private String eventName(AgentEvent evt) {
        return switch (evt) {
            case AgentEvent.Meta m -> "meta";
            case AgentEvent.ThinkingDelta t -> "thinking_delta";
            case AgentEvent.MessageDelta m -> "message_delta";
            case AgentEvent.ToolCall t -> "tool_call";
            case AgentEvent.ToolResult t -> "tool_result";
            case AgentEvent.TurnDone t -> "turn_done";
            case AgentEvent.TurnError t -> "error";
        };
    }

    private Map<String, Object> payload(AgentEvent evt, long seq) {
        return switch (evt) {
            case AgentEvent.Meta m -> Map.of("turnId", m.turnId(), "conversationId", m.conversationId(),
                "model", m.model(), "seq", seq);
            case AgentEvent.ThinkingDelta t -> Map.of("turnId", t.turnId(), "content", t.content(), "seq", seq);
            case AgentEvent.MessageDelta m -> Map.of("turnId", m.turnId(), "content", m.content(), "seq", seq);
            case AgentEvent.ToolCall t -> Map.of("turnId", t.turnId(), "callId", t.callId(),
                "toolName", t.toolName(), "arguments", t.arguments(), "seq", seq);
            case AgentEvent.ToolResult t -> Map.of("turnId", t.turnId(), "callId", t.callId(),
                "toolName", t.toolName(), "result", t.result(),
                "durationMs", t.durationMs(), "success", t.success(), "seq", seq);
            case AgentEvent.TurnDone t -> Map.of("turnId", t.turnId(), "finishReason", t.finishReason(),
                "usage", t.usage(), "seq", seq);
            case AgentEvent.TurnError e -> Map.of("turnId", e.turnId(), "code", e.code(),
                "message", e.message(), "seq", seq);
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }
}
