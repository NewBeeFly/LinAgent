package com.linagent.agent.stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

/**
 * 探针：确认 StepFun 流式响应中 reasoning_content 在 Spring AI ChatResponse 里的落点。
 * 结论记录到本类的 CONCLUSION 常量（ThinkingExtractor 实现依据）。
 */
@Tag("manual")
@SpringBootTest
class StepFunStreamingProbeTest {

    /**
     * 探针结论（2026-09-20 实测，spring-ai-openai 1.1.2 + step-3.7-flash，共 159 个 chunk）：
     * thinking 落在 result.output.metadata 的 "reasoningContent" 键（SSE 原始字段为 reasoning_content，
     * 经 OpenAiApi.ChatCompletionMessage.reasoningContent → buildGeneration → AssistantMessage.properties 映射）。
     * 分片规律：思考期 chunk text=""、reasoningContent=思考增量；正文期 chunk reasoningContent=""、
     * text=正文增量；切换边界存在个别 chunk 同时携带 reasoning 尾与 text 首（实测 reasoningContent=。
     * 且 text=###）。text 恒非 null（无内容时为空串 ""）；末 chunk finishReason=STOP，其后另有 1 个
     * 无 choices 的 usage 尾包（ChatResponse.getResult()==null，消费方必须判空）。
     */
    static final String CONCLUSION =
        "thinking 落在 result.output.metadata.reasoningContent（每 chunk 思考增量；与正文增量基本互斥，切换边界个别 chunk 两者同现）";

    @Autowired
    org.springframework.ai.chat.model.ChatModel chatModel;

    @Test
    void probeStreamingShape() {
        Flux<ChatResponse> flux = chatModel.stream(
            new Prompt("1+1等于几？先思考再回答"));
        flux.doOnNext(r -> {
            System.out.println("=== chunk ===");
            // 实测存在无 choices 的尾包（usage 包），getResult() 为 null，需防护
            if (r.getResult() == null) {
                System.out.println("results empty; responseMetadata=" + r.getMetadata());
                return;
            }
            System.out.println("text=" + r.getResult().getOutput().getText());
            System.out.println("outputMetadata=" + r.getResult().getOutput().getMetadata());
            System.out.println("responseMetadata=" + r.getMetadata());
        }).blockLast();
    }
}
