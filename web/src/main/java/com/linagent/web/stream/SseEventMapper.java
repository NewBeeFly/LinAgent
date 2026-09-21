package com.linagent.web.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagent.agent.facade.AgentEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
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

    /**
     * null 容错构造：任一字段为 null（如 NPE 类异常的 getMessage() 为 null）时该字段缺省，
     * 而非 Map.of 的 NPE 打穿 Flux 中断流。seq 恒存在。
     */
    private Map<String, Object> payload(AgentEvent evt, long seq) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (evt) {
            case AgentEvent.Meta m -> {
                putIfNotNull(payload, "turnId", m.turnId());
                putIfNotNull(payload, "conversationId", m.conversationId());
                putIfNotNull(payload, "model", m.model());
            }
            case AgentEvent.ThinkingDelta t -> {
                putIfNotNull(payload, "turnId", t.turnId());
                putIfNotNull(payload, "content", t.content());
            }
            case AgentEvent.MessageDelta m -> {
                putIfNotNull(payload, "turnId", m.turnId());
                putIfNotNull(payload, "content", m.content());
            }
            case AgentEvent.ToolCall t -> {
                putIfNotNull(payload, "turnId", t.turnId());
                putIfNotNull(payload, "callId", t.callId());
                putIfNotNull(payload, "toolName", t.toolName());
                putIfNotNull(payload, "arguments", t.arguments());
            }
            case AgentEvent.ToolResult t -> {
                putIfNotNull(payload, "turnId", t.turnId());
                putIfNotNull(payload, "callId", t.callId());
                putIfNotNull(payload, "toolName", t.toolName());
                putIfNotNull(payload, "result", t.result());
                putIfNotNull(payload, "durationMs", t.durationMs());
                putIfNotNull(payload, "success", t.success());
            }
            case AgentEvent.TurnDone t -> {
                putIfNotNull(payload, "turnId", t.turnId());
                putIfNotNull(payload, "finishReason", t.finishReason());
                putIfNotNull(payload, "usage", t.usage());
            }
            case AgentEvent.TurnError e -> {
                putIfNotNull(payload, "turnId", e.turnId());
                putIfNotNull(payload, "code", e.code());
                putIfNotNull(payload, "message", e.message());
            }
        }
        payload.put("seq", seq);
        return payload;
    }

    private static void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }
}
