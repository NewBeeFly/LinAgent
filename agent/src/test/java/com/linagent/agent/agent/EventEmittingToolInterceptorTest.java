package com.linagent.agent.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.linagent.agent.facade.AgentEvent;
import com.linagent.agent.facade.SegmentBuffer;
import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventEmittingToolInterceptorTest {

    static class RecordingMessageRepo implements MessageRepository {
        final List<Message> saved = new ArrayList<>();
        @Override public <S extends Message> S save(S entity) { saved.add(entity); return entity; }
        @Override public <S extends Message> List<S> saveAll(Iterable<S> entities) {
            List<S> result = new ArrayList<>();
            entities.forEach(e -> result.add(save(e)));
            return result;
        }
        @Override public java.util.Optional<Message> findById(Long id) { return java.util.Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public List<Message> findAll() { return List.of(); }
        @Override public List<Message> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void deleteById(Long id) {}
        @Override public void delete(Message entity) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends Message> entities) {}
        @Override public void deleteAll() {}
        @Override public List<Message> findByTurnIdOrderBySeq(Long turnId) { return List.of(); }
        @Override public List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId) { return List.of(); }
    }

    private final RecordingMessageRepo repo = new RecordingMessageRepo();
    private final List<Object> emitted = new ArrayList<>();

    private ToolCallRequest request() {
        return ToolCallRequest.builder()
            .toolCallId("call-1")
            .toolName("list_dir")
            .arguments("{\"path\":\".\"}")
            .context(Map.of())
            .build();
    }

    @Test
    void successEmitsToolCallAndToolResultAndPersistsBoth() {
        SegmentBuffer buffer = new SegmentBuffer(7L, repo);
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<Object> side = sink.asFlux().doOnNext(emitted::add);
        EventEmittingToolInterceptor interceptor = new EventEmittingToolInterceptor(sink, buffer, 7L);

        ToolCallResponse response = interceptor.interceptToolCall(request(),
            req -> ToolCallResponse.of("call-1", "list_dir", "目录内容"));

        sink.tryEmitComplete();
        StepVerifier.create(side).expectNextCount(2).verifyComplete();

        assertThat(response.getResult()).isEqualTo("目录内容");
        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0)).isInstanceOf(AgentEvent.ToolCall.class);
        AgentEvent.ToolResult result = (AgentEvent.ToolResult) emitted.get(1);
        assertThat(result.callId()).isEqualTo("call-1");
        assertThat(result.success()).isTrue();
        assertThat(result.result()).isEqualTo("目录内容");

        assertThat(repo.saved).extracting(Message::msgType)
            .containsExactly("TOOL_CALL", "TOOL_RESULT");
        assertThat(repo.saved.get(0).arguments()).isEqualTo("{\"path\":\".\"}");
        assertThat(repo.saved.get(1).success()).isTrue();
    }

    @Test
    void toolFailureFallsBackToErrorMessageWithoutBreakingLoop() {
        SegmentBuffer buffer = new SegmentBuffer(7L, repo);
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<Object> side = sink.asFlux().doOnNext(emitted::add);
        EventEmittingToolInterceptor interceptor = new EventEmittingToolInterceptor(sink, buffer, 7L);

        ToolCallResponse response = interceptor.interceptToolCall(request(), req -> {
            throw new IllegalStateException("文件不存在");
        });

        sink.tryEmitComplete();
        StepVerifier.create(side).expectNextCount(2).verifyComplete();

        // spec §7：回传错误文本给模型，不向调用方抛异常（ReAct 循环继续）
        assertThat(response.getResult()).isEqualTo("Tool failed: 文件不存在");
        AgentEvent.ToolResult result = (AgentEvent.ToolResult) emitted.get(1);
        assertThat(result.success()).isFalse();
        assertThat(result.result()).isEqualTo("Tool failed: 文件不存在");

        assertThat(repo.saved).extracting(Message::msgType)
            .containsExactly("TOOL_CALL", "TOOL_RESULT");
        assertThat(repo.saved.get(1).success()).isFalse();
    }

    @Test
    void toolCallFlushesPendingThinkingSegmentBeforeSavingToolMessage() {
        SegmentBuffer buffer = new SegmentBuffer(7L, repo);
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        EventEmittingToolInterceptor interceptor = new EventEmittingToolInterceptor(sink, buffer, 7L);

        buffer.appendThinking("调用前的思考");
        interceptor.interceptToolCall(request(), req -> ToolCallResponse.of("call-1", "list_dir", "ok"));

        assertThat(repo.saved).extracting(Message::msgType)
            .containsExactly("THINKING", "TOOL_CALL", "TOOL_RESULT");
    }
}
