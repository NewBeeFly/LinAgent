package com.linagent.agent.stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThinkingTapChatModel 装饰器测试。
 * public：AgentFactoryTest 复用内部类 ChatModelStub 做装配冒烟（跨包访问需要）。
 */
public class ThinkingTapChatModelTest {

    private final ThinkingExtractor extractor = new OpenAiThinkingExtractor();
    private final ChatModelStub delegate = new ChatModelStub();

    private ChatResponse chunk(String text, Map<String, Object> meta) {
        // spring-ai 1.1.2 AssistantMessage 无 public 双参构造器，走 builder（同 OpenAiThinkingExtractorTest）
        AssistantMessage msg = AssistantMessage.builder()
            .content(text)
            .properties(meta)
            .build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    @Test
    void thinkingDeltasAreEmittedToSink() {
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<Object> usage = new AtomicReference<>();
        delegate.next = Flux.just(
            chunk(null, Map.of("reasoningContent", "思考A")),
            chunk(null, Map.of("reasoningContent", "思考B")),
            chunk("正文", Map.of()));

        ThinkingTapChatModel tapped = new ThinkingTapChatModel(delegate, sink, extractor, usage);

        tapped.stream(new Prompt("hi")).blockLast();
        // 手动 sink 不会随上游完成而关闭，collectList 前显式封口，避免 block 悬挂
        sink.tryEmitComplete();

        assertThat(sink.asFlux().collectList().block())
            .extracting(Object::toString)
            .containsExactly("思考A", "思考B");
    }

    /**
     * Task 3 探针结论：流末尾会出现无 choices 的 usage 尾包（getResults 为空，仅 metadata 携带 usage）。
     * tap 必须对该尾包判空防护且捕获 usage，不得 NPE、不得向 thinking sink 发射。
     */
    @Test
    void usageTailPacketWithoutResultsIsGuardedAndCaptured() {
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<Object> usage = new AtomicReference<>();
        delegate.next = Flux.just(
            chunk("正文", Map.of()),
            new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 20)).build()));

        ThinkingTapChatModel tapped = new ThinkingTapChatModel(delegate, sink, extractor, usage);

        tapped.stream(new Prompt("hi")).blockLast();
        sink.tryEmitComplete();

        assertThat(usage.get()).isInstanceOf(DefaultUsage.class);
        assertThat(((org.springframework.ai.chat.metadata.Usage) usage.get()).getPromptTokens()).isEqualTo(10);
        assertThat(((org.springframework.ai.chat.metadata.Usage) usage.get()).getCompletionTokens()).isEqualTo(20);
        assertThat(sink.asFlux().collectList().block()).isEmpty();
    }

    @Test
    void bodyChunksPassThroughUnchanged() {
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<Object> usage = new AtomicReference<>();
        delegate.next = Flux.just(chunk("正文1", Map.of()), chunk("正文2", Map.of()));

        ThinkingTapChatModel tapped = new ThinkingTapChatModel(delegate, sink, extractor, usage);

        List<ChatResponse> passed = tapped.stream(new Prompt("hi")).collectList().block();
        sink.tryEmitComplete();

        assertThat(passed).hasSize(2);
        assertThat(sink.asFlux().collectList().block()).isEmpty();
    }

    @Test
    void callDelegatesUntouched() {
        ThinkingTapChatModel tapped = new ThinkingTapChatModel(delegate,
            Sinks.many().unicast().onBackpressureBuffer(), extractor, new AtomicReference<>());

        assertThat(tapped.call(new Prompt("hi")).getResult().getOutput().getText()).isEqualTo("ok");
    }

    /** 最小可用的 ChatModel 桩：stream 返回 next，call 返回单条（AgentFactoryTest 复用） */
    public static class ChatModelStub implements org.springframework.ai.chat.model.ChatModel {
        Flux<ChatResponse> next = Flux.empty();

        @Override
        public ChatResponse call(Prompt prompt) {
            return chunk("ok", Map.of());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return next;
        }

        private ChatResponse chunk(String text, Map<String, Object> meta) {
            AssistantMessage msg = AssistantMessage.builder()
                .content(text)
                .properties(meta)
                .build();
            return new ChatResponse(List.of(new Generation(msg)));
        }
    }
}
