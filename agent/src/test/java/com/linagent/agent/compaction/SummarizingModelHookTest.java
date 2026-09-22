package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SummarizingModelHookTest {

    private final ChatModel model = mock(ChatModel.class);

    private SummarizingModelHook hook(int thresholdTokens, int keepTurns) {
        return new SummarizingModelHook(model, ctx -> {}, thresholdTokens, keepTurns, 2,
            Duration.ofSeconds(60));
    }

    private RunnableConfig config() {
        return RunnableConfig.builder().threadId("t-hook").build();
    }

    @Test
    void belowThresholdPassesThrough() {
        List<Message> msgs = List.of(new UserMessage("hi"));
        // 4 字符 / 2 = 2 tokens << 阈值
        assertThat(hook(10_000, 20).compact(msgs, config())).isNull();
    }

    @Test
    void estimationCountsCharsPerTokenAcrossMessageKinds() {
        // UserMessage 40 字符 / 2 = 20；ToolResponseMessage responseData 10 字符 / 2 = 5 → 共 25
        org.springframework.ai.chat.messages.ToolResponseMessage toolResp =
            org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                    "c1", "csv_summary", "1234567890")))
                .build();
        List<Message> msgs = List.of(new UserMessage("a".repeat(40)), toolResp);
        // 阈值 25 → 放行；阈值 24 → 触发（但不足 20 轮，切割守卫放行）——两者都返 null，
        // 故用包可见 estimateTokens 直测口径：
        assertThat(hook(1, 20).estimateTokens(msgs)).isEqualTo(20 + 5);
    }
}
