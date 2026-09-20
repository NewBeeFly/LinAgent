package com.javaagent.agent.stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiThinkingExtractorTest {

    private final OpenAiThinkingExtractor extractor = new OpenAiThinkingExtractor();

    private ChatResponse response(String text, Map<String, Object> outputMetadata) {
        // spring-ai 1.1.2 无 public 两参构造器，改用 builder（探针确认 reasoning 落在 metadata）
        AssistantMessage msg = AssistantMessage.builder()
            .content(text)
            .properties(outputMetadata)
            .build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    @Test
    void extractsThinkingFromOutputMetadata() {
        ChatResponse r = response(null, Map.of("reasoningContent", "思考中"));
        assertThat(extractor.thinkingDelta(r)).contains("思考中");
        assertThat(extractor.textDelta(r)).isEmpty();
    }

    @Test
    void extractsTextWhenPresent() {
        ChatResponse r = response("正文内容", Map.of());
        assertThat(extractor.textDelta(r)).contains("正文内容");
        assertThat(extractor.thinkingDelta(r)).isEmpty();
    }

    @Test
    void emptyResponseYieldsNothing() {
        ChatResponse r = response(null, Map.of());
        assertThat(extractor.thinkingDelta(r)).isEmpty();
        assertThat(extractor.textDelta(r)).isEmpty();
    }

    /** 探针实测：切换边界个别 chunk 同时携带 reasoning 尾与 text 首（reasoningContent=。 且 text=###） */
    @Test
    void boundaryChunkYieldsBothDeltas() {
        ChatResponse r = response("###", Map.of("reasoningContent", "。"));
        assertThat(extractor.thinkingDelta(r)).contains("。");
        assertThat(extractor.textDelta(r)).contains("###");
    }

    /** 探针实测：流末尾存在无 choices 的 usage 尾包，消费方会拿到空结果响应 */
    @Test
    void nullResponseOrEmptyResultsYieldNothing() {
        assertThat(extractor.thinkingDelta(null)).isEmpty();
        assertThat(extractor.textDelta(null)).isEmpty();
        assertThat(extractor.thinkingDelta(new ChatResponse(List.of()))).isEmpty();
        assertThat(extractor.textDelta(new ChatResponse(List.of()))).isEmpty();
    }
}
