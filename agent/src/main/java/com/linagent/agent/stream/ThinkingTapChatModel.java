package com.linagent.agent.stream;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 装饰器：把流式响应中的 thinking 增量旁路发布到 thinkingSink（String），
 * usage 记录到 AtomicReference，正文照常透传给 ReactAgent 的 LLM 节点。
 * SAA 的 LLM 节点会把 ChatResponse 归并为文本 chunk，thinking 不会出现在 NodeOutput 里，
 * 因此必须在 ChatModel 层拦截 —— 这是 thinking 链路的兜底方案（spec §8 风险预案）。
 *
 * 判空防护：流末尾会出现无 choices 的 usage 尾包（Task 3 探针实测，getResults 为空），
 * extractor 内部已对空 results 返回 empty，usage 读取前判 metadata 非空。
 */
public class ThinkingTapChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Sinks.Many<Object> thinkingSink;
    private final ThinkingExtractor extractor;
    private final AtomicReference<Object> usageCapture;

    public ThinkingTapChatModel(ChatModel delegate, Sinks.Many<Object> thinkingSink,
                                ThinkingExtractor extractor, AtomicReference<Object> usageCapture) {
        this.delegate = delegate;
        this.thinkingSink = thinkingSink;
        this.extractor = extractor;
        this.usageCapture = usageCapture;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
            .doOnNext(response -> {
                extractor.thinkingDelta(response)
                    .ifPresent(delta -> thinkingSink.tryEmitNext(delta));
                if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    usageCapture.set(response.getMetadata().getUsage());
                }
            });
    }
}
