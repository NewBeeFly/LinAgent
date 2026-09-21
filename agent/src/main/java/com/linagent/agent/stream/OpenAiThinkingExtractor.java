package com.linagent.agent.stream;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从 OpenAI 兼容流式响应中区分 thinking 与正文。
 * 落点（探针实测 2026-09-20，见 StepFunStreamingProbeTest.CONCLUSION）：
 * reasoning_content（SSE 原始字段）经 spring-ai-openai 映射为 result.output.metadata 的
 * "reasoningContent" 键，每 chunk 携带该次思考增量，与正文增量基本互斥（切换边界个别 chunk 两者同现）。
 * CANDIDATE_KEYS 保留同义键兜底，以兼容其它 OpenAI 兼容厂商。
 */
@Component
public class OpenAiThinkingExtractor implements ThinkingExtractor {

    static final List<String> CANDIDATE_KEYS = List.of("reasoningContent", "reasoning_content", "reasoning");

    @Override
    public Optional<String> thinkingDelta(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> outputMeta = response.getResults().get(0).getOutput().getMetadata();
        if (outputMeta != null) {
            for (String key : CANDIDATE_KEYS) {
                Object v = outputMeta.get(key);
                if (v instanceof String s && !s.isBlank()) {
                    return Optional.of(s);
                }
            }
        }
        if (response.getMetadata() != null && response.getMetadata().get("reasoningContent") instanceof String s) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> textDelta(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return Optional.empty();
        }
        String text = response.getResults().get(0).getOutput().getText();
        return (text == null || text.isEmpty()) ? Optional.empty() : Optional.of(text);
    }
}
