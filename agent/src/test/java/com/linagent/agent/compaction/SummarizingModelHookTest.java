package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.linagent.agent.skills.ResidentPromptBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SummarizingModelHookTest {

    private final ChatModel model = mock(ChatModel.class);

    /** ResidentPromptBuilder 为非 final 具体类，mock + 桩 build() 即可（不做任何重构抽取） */
    private static ResidentPromptBuilder stubPrompt() {
        ResidentPromptBuilder builder = mock(ResidentPromptBuilder.class);
        when(builder.build()).thenReturn("stub-system-prompt");
        return builder;
    }

    private SummarizingModelHook hook(int thresholdTokens, int keepTurns) {
        return new SummarizingModelHook(model, ctx -> {}, stubPrompt(), thresholdTokens, keepTurns, 2,
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

    // ===== Task 3：摘要调用与结果组装

    private org.springframework.ai.chat.model.ChatResponse summaryResponse(String text) {
        org.springframework.ai.chat.messages.AssistantMessage msg =
            org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content(text).properties(java.util.Map.of()).build();
        return new org.springframework.ai.chat.model.ChatResponse(
            List.of(new org.springframework.ai.chat.model.Generation(msg)));
    }

    @Test
    void compactReusesPrefixAndAppendsInstruction() {
        // 30 轮，keepTurns=20 → cutoff=20：摘要请求 = [SystemMessage(stub system prompt),
        // msgs[0..20) 原样（含首条 u0）, 尾部指令]——与主调用 [systemPrompt, u0, a0, ...]
        // 从首位起前缀对齐（cache-safe，StepFun 从首 token 比对）
        java.util.List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            msgs.add(new UserMessage("u" + i + " ".repeat(100))); // 撑大估算触发阈值
            msgs.add(new AssistantMessage("a" + i));
        }
        List<org.springframework.ai.chat.prompt.Prompt> captured = new java.util.ArrayList<>();
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return summaryResponse("压缩后的摘要正文");
        });
        List<Message> result = hook(1, 20).compact(msgs, config());

        assertThat(result).isNotNull();
        // 摘要请求：首位是与主调用逐字节对齐的 system prompt；中段 [1, n-1) 与 msgs.subList(0, 20)
        // 逐位同引用（cache-safe 前缀）；末条为指令 UserMessage
        List<Message> req = captured.get(0).getInstructions();
        assertThat(req.get(req.size() - 1).getText()).isEqualTo(SummarizingModelHook.COMPACT_INSTRUCTION);
        assertThat(req.get(0)).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(req.get(0).getText()).isEqualTo("stub-system-prompt");
        assertThat(req.subList(1, req.size() - 1))
            .zipSatisfy(msgs.subList(0, 20), (actual, expected) -> assertThat(actual).isSameAs(expected));
        // 结果结构：[Sys(摘要), 首条u0, ...保留区(u10 起=msgs[20..60))]
        assertThat(result.get(0)).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(result.get(0).getText()).startsWith(SummarizingModelHook.SUMMARY_PREFIX)
            .contains("压缩后的摘要正文");
        assertThat(result.get(1).getText()).startsWith("u0");
        assertThat(result.subList(2, result.size()))
            .containsExactlyElementsOf(msgs.subList(20, msgs.size()));
    }

    @Test
    void summaryFailureKeepsOriginalList() {
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenThrow(new RuntimeException("boom"));
        List<Message> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            msgs.add(new UserMessage("u" + i + " ".repeat(100)));
            msgs.add(new AssistantMessage("a" + i));
        }
        assertThat(hook(1, 20).compact(msgs, config())).isNull();
    }

    @Test
    void summaryTimeoutKeepsOriginalList() {
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return summaryResponse("迟到的摘要");
        });
        SummarizingModelHook quick = new SummarizingModelHook(model, ctx -> {}, stubPrompt(), 1, 20, 2,
            java.time.Duration.ofMillis(100));
        List<Message> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            msgs.add(new UserMessage("u" + i + " ".repeat(100)));
            msgs.add(new AssistantMessage("a" + i));
        }
        assertThat(quick.compact(msgs, config())).isNull();
    }

    @Test
    void priorSummarySystemMessageGetsReSummarizedExactlyOnce() {
        // 头部已有旧摘要 SystemMessage（上次 REPLACE 的产物）：本次触发时随被摘区一起
        // 进入摘要请求（合并语义），结果里新摘要恰 1 份、旧摘要文本不再独立出现
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenReturn(summaryResponse("合并后的新摘要"));
        List<Message> msgs = new java.util.ArrayList<>();
        msgs.add(new org.springframework.ai.chat.messages.SystemMessage(
            SummarizingModelHook.SUMMARY_PREFIX + "旧摘要内容"));
        for (int i = 0; i < 30; i++) {
            msgs.add(new UserMessage("u" + i + " ".repeat(100)));
            msgs.add(new AssistantMessage("a" + i));
        }
        List<Message> result = hook(1, 20).compact(msgs, config());

        assertThat(result).isNotNull();
        long summaryCount = result.stream()
            .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
            .count();
        assertThat(summaryCount).isEqualTo(1); // 不叠加
        assertThat(result.get(0).getText()).contains("合并后的新摘要");
        // 旧摘要进入了摘要请求（被合并而非丢弃）：请求首位为 system prompt，旧摘要 SystemMessage
        // 紧随其后（index 1），其后才是原始对话历史
        org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        org.mockito.Mockito.verify(model).call(captor.capture());
        List<Message> req = captor.getValue().getInstructions();
        assertThat(req.get(0).getText()).isEqualTo("stub-system-prompt");
        assertThat(req.get(1).getText()).contains("旧摘要内容");
    }

    @Test
    void sinkNotifiedWithContext() {
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenReturn(summaryResponse("S"));
        List<java.util.List<Object>> received = new java.util.ArrayList<>();
        SummarizingModelHook h = new SummarizingModelHook(model, ctx -> received.add(List.of(
            ctx.threadId(), ctx.summary(), ctx.messagesBefore(), ctx.messagesAfter())), stubPrompt(), 1, 20, 2,
            Duration.ofSeconds(60));
        List<Message> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            msgs.add(new UserMessage("u" + i + " ".repeat(100)));
            msgs.add(new AssistantMessage("a" + i));
        }
        h.compact(msgs, config());
        assertThat(received).hasSize(1);
        assertThat(received.get(0).get(0)).isEqualTo("t-hook");
        assertThat(received.get(0).get(1)).isEqualTo("S");
        assertThat((int) received.get(0).get(2)).isEqualTo(60);   // 摘要前 60 条
        assertThat((int) received.get(0).get(3)).isEqualTo(42);   // 摘要后 1+1+40（保留区 msgs[20..60)）
    }
}
