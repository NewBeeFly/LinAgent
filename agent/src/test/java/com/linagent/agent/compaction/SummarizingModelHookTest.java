package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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

    // ===== Task 2：切割

    @Test
    void cutoffFallsOnKthUserMessageFromTail() {
        // 25 轮 [u0,a0,u1,a1,...,u24,a24]，keepTurns=20 → 切割点 = u5 的下标 10
        List<Message> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            msgs.add(new UserMessage("u" + i));
            msgs.add(new AssistantMessage("a" + i));
        }
        assertThat(hook(1, 20).findTurnCutoff(msgs)).isEqualTo(10);
    }

    @Test
    void fewerThanKTurnsReturnsZero() {
        List<Message> msgs = List.of(new UserMessage("u0"), new AssistantMessage("a0"));
        assertThat(hook(1, 20).findTurnCutoff(msgs)).isZero();
    }

    @Test
    void cutoffBacksOffToAvoidSplittingToolPair() {
        // 结构：[u0, ..., u19, u20, assistant(toolCalls), u21, ...]——注意第 20 轮（倒数第 20 个
        // UserMessage 为 u5 类场景不构造），此处直接构造目标切割点落在配对中间的场景：
        // 倒数第 20 个 UserMessage 之后紧跟 assistant(toolCalls)，其 ToolResponse 在切割点后
        org.springframework.ai.chat.messages.AssistantMessage withCalls =
            AssistantMessage.builder().content("").properties(java.util.Map.of())
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "t", "{}")))
                .build();
        // 适配说明：Spring AI 1.1.2 的 ToolResponseMessage 无 List 参 public 构造器，
        // ToolResponse 为 3 参 record (id, name, responseData)，须走 builder（Task 1 同款适配）
        org.springframework.ai.chat.messages.ToolResponseMessage toolResp =
            org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                    "call-1", "csv_summary", "R")))
                .build();
        List<Message> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            msgs.add(new UserMessage("u" + i));
            msgs.add(new AssistantMessage("a" + i));
        }
        // 第 21 轮（保留区尾部之外）：u20 + assistant(toolCalls) + toolResp
        msgs.add(new UserMessage("u20"));
        msgs.add(withCalls);
        msgs.add(toolResp);
        msgs.add(new AssistantMessage("a20"));
        // keepTurns=20 → 名义切割点在 u0？不——总 21 轮，倒数第 20 个 UserMessage = u1（下标 2）。
        // 名义切割点 2 之后紧跟 a1、u2...均安全；真正的配对跨越场景：
        // 构造 keepTurns=1 → 切割点 = u20（下标 40），其前是 toolResp、withCalls 在切割点后 →
        // assistant 在切割点后、其配对 toolResp 在切割点前 → 不安全 → 回退到 u20 前一格仍不安全
        //（toolResp/withCalls 仍跨）→ 继续回退到 withCalls 下标（41-1=40？以实现回退到安全点）
        int cutoff = hook(1, 1).findTurnCutoff(msgs);
        // 安全点必须使 withCalls 与 toolResp 同侧：cutoff <= withCalls 下标 或 > toolResp 下标
        int withCallsIdx = msgs.indexOf(withCalls);
        int toolRespIdx = msgs.indexOf(toolResp);
        assertThat(cutoff).satisfiesAnyOf(
            c -> assertThat(c).isLessThanOrEqualTo(withCallsIdx),
            c -> assertThat(c).isGreaterThan(toolRespIdx));
    }

    @Test
    void cutoffBacksOffWhenNominalPointSplitsToolPair() {
        // [u0, withCalls, u1, toolResp] + keepTurns=1 → 名义切割点 = u1(idx 2)，
        // 但 withCalls(1) 在摘侧、toolResp(3) 在保留侧 → 跨侧不安全 → 回退到 1
        //（u0 摘，withCalls/toolResp 同在保留侧 → 安全）
        AssistantMessage withCalls = AssistantMessage.builder().content("").properties(java.util.Map.of())
            .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "t", "{}")))
            .build();
        ToolResponseMessage toolResp = ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "csv_summary", "R")))
            .build();
        List<Message> msgs = List.of(new UserMessage("u0"), withCalls, new UserMessage("u1"), toolResp);
        assertThat(hook(1, 1).findTurnCutoff(msgs)).isEqualTo(1); // 名义点 2 被回退到 1
    }

    @Test
    void isSafeCutoffPointDirectCheck() {
        org.springframework.ai.chat.messages.AssistantMessage withCalls =
            AssistantMessage.builder().content("").properties(java.util.Map.of())
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "t", "{}")))
                .build();
        // 适配说明：同上——ToolResponseMessage 走 builder + 3 参 ToolResponse（id 匹配语义不变）
        org.springframework.ai.chat.messages.ToolResponseMessage toolResp =
            org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                    "c1", "csv_summary", "R")))
                .build();
        SummarizingModelHook h = hook(1, 1);
        List<Message> pair = List.of(withCalls, toolResp);
        // 切割点 k 语义：[0,k) 被摘、[k,*) 保留。pair 中 withCalls=0、toolResp=1：
        assertThat(h.isSafeCutoffPoint(pair, 0)).isTrue();  // 两者都在保留侧（同侧安全）
        assertThat(h.isSafeCutoffPoint(pair, 1)).isFalse(); // withCalls 被摘、toolResp 保留 → 孤儿 ToolResponse
        assertThat(h.isSafeCutoffPoint(pair, 2)).isTrue();  // 两者都被摘（同侧安全）
    }
}
