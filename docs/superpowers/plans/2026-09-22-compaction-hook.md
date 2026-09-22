# 压缩策略 hook 化重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以自定义 `MessagesModelHook`（BEFORE_MODEL + REPLACE）替换 CompactionService 换代体系：真实消息计数、保最近 20 turn、cache-safe 摘要、根治摘要重复注入。

**Architecture:** hook 在每次模型调用前对 state messages 估算 token，超阈值时切割（turn 边界 + 工具配对保护 + 首条 UserMessage 永存），摘要调用复用原消息前缀 + 尾部压缩指令，成功则 `UpdatePolicy.REPLACE` 原地替换（不换 threadId、不碰展示存储），失败/超时原样放行。`AgentFacade` 纯化为只传当前 UserMessage。

**Tech Stack:** SAA 1.1.2.3（MessagesModelHook/AgentCommand/UpdatePolicy/HookPositions）、Spring AI 1.1.2（ChatModel/Prompt）、Reactor（boundedElastic + block 超时）、JUnit5 + Mockito + AssertJ + Testcontainers PG。

**Spec:** `docs/superpowers/specs/2026-09-22-compaction-hook-design.md`（含全部实证依据表，实施前必读）

## Global Constraints

- 分支：`feat/compaction-hook`（Task 1 创建），全部提交落此分支
- SAA API 签名以本地 sources jar 为准：`MessagesModelHook.beforeModel(List<Message>, RunnableConfig)` 返回 `AgentCommand`；`new AgentCommand(list)` 默认 REPLACE，`new AgentCommand(list, UpdatePolicy.REPLACE)` 显式；`AgentCommand.getMessages()` **包私有，外部不可读**——核心逻辑必须收在包可见纯函数 `compact()` 里供单测直测
- `@HookPositions(HookPosition.BEFORE_MODEL)` 类注解；须 override `getName()` 与 `canJumpTo()`（返回 `List.of()`）
- 展示存储（turn/message 表）与 `Conversation` PO 的 DDL 零变更；`compact_summary`/`compacted_turn_seq` 列保留、代码停读写（创建时传 `null, null`）
- 错误信息（摘要失败/超时）永不进入模型上下文；失败 = 原样放行，本轮跳过下轮再试
- thinking 不处理（实证：请求构造只调 getText/getToolCalls/getMedia，metadata 不回传）
- Java 方法驼峰；不写 JdbcTemplate 裸 SQL；PO/仓库不动
- 每任务收尾跑该任务测试 + `mvn -pl agent test`（agent 模块改动后先 `mvn -pl agent install -DskipTests` 再跑 web，见 CLAUDE.md）

---

### Task 1: 分支创建 + SummarizingModelHook 骨架（token 估算 + 阈值放行）

**Files:**
- Create: `agent/src/main/java/com/linagent/agent/compaction/SummarizingModelHook.java`
- Create: `agent/src/main/java/com/linagent/agent/compaction/CompactionSummarySink.java`
- Test: `agent/src/test/java/com/linagent/agent/compaction/SummarizingModelHookTest.java`

**Interfaces:**
- Consumes: SAA `MessagesModelHook`/`AgentCommand`（上述签名）
- Produces: `SummarizingModelHook` 构造器 `(ChatModel, CompactionSummarySink, int thresholdTokens, int keepTurns, int charsPerToken, Duration summaryTimeout)`；包可见 `List<Message> compact(List<Message> previousMessages, RunnableConfig config)`（null=放行，非null=REPLACE 新列表）；`CompactionSummarySink`（`void onSummary(SummaryContext ctx)` + `record SummaryContext(String threadId, String summary, int messagesBefore, int messagesAfter)`）；常量 `SUMMARY_PREFIX = "此前对话摘要：\n"`、`COMPACT_INSTRUCTION`（Task 3 使用）

- [ ] **Step 1: 创建分支**

```bash
git checkout -b feat/compaction-hook
```

- [ ] **Step 2: 写失败测试（骨架行为：低阈值放行、估算口径）**

```java
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
            new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("c1", "1234567890")));
        List<Message> msgs = List.of(new UserMessage("a".repeat(40)), toolResp);
        // 阈值 25 → 放行；阈值 24 → 触发（但不足 20 轮，切割守卫放行）——两者都返 null，
        // 故用包可见 estimateTokens 直测口径：
        assertThat(hook(1, 20).estimateTokens(msgs)).isEqualTo(20 + 5);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=SummarizingModelHookTest`
Expected: 编译失败（SummarizingModelHook/CompactionSummarySink 不存在）

- [ ] **Step 4: 最小实现**

`CompactionSummarySink.java`：

```java
package com.linagent.agent.compaction;

/**
 * 压缩摘要产出接缝：跨会话任务接力预留（spec §5.3），MVP 由 LoggingSummarySink 打日志。
 */
public interface CompactionSummarySink {

    void onSummary(SummaryContext ctx);

    record SummaryContext(String threadId, String summary, int messagesBefore, int messagesAfter) {}
}
```

`SummarizingModelHook.java`：

```java
package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.List;

/**
 * BEFORE_MODEL 全量摘要 hook（spec：docs/superpowers/specs/2026-09-22-compaction-hook-design.md）。
 * 超阈值时保最近 K 个完整 turn + 首条 UserMessage，其余经 cache-safe 摘要调用 REPLACE 原地
 * 替换——不换 threadId、不触碰展示存储。摘要失败/超时返回原列表，错误永不进上下文。
 *
 * 可测性设计：AgentCommand.getMessages() 包私有（SAA 外部不可读），核心逻辑收在包可见
 * compact()（null=放行 / 非 null=REPLACE 新列表），beforeModel 仅做 AgentCommand 包装。
 */
@HookPositions(HookPosition.BEFORE_MODEL)
public class SummarizingModelHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(SummarizingModelHook.class);

    static final String SUMMARY_PREFIX = "此前对话摘要：\n";
    static final String COMPACT_INSTRUCTION = """
        请把以上对话历史（含此前的压缩摘要）压缩为一段忠实、信息完备的摘要：保留用户目标、
        已完成的关键操作与结论、未决事项。直接输出摘要正文，不要任何前后缀。""";

    private final ChatModel chatModel;
    private final CompactionSummarySink summarySink;
    private final int thresholdTokens;
    private final int keepTurns;
    private final int charsPerToken;
    private final Duration summaryTimeout;

    public SummarizingModelHook(ChatModel chatModel, CompactionSummarySink summarySink,
                                int thresholdTokens, int keepTurns, int charsPerToken,
                                Duration summaryTimeout) {
        this.chatModel = chatModel;
        this.summarySink = summarySink;
        this.thresholdTokens = thresholdTokens;
        this.keepTurns = keepTurns;
        this.charsPerToken = charsPerToken;
        this.summaryTimeout = summaryTimeout;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        List<Message> compacted = compact(previousMessages, config);
        return compacted == null
            ? new AgentCommand(previousMessages)
            : new AgentCommand(compacted);
    }

    /** null=放行；非null=REPLACE 新列表（Task 2/3 完成切割与摘要） */
    List<Message> compact(List<Message> previousMessages, RunnableConfig config) {
        int totalTokens = estimateTokens(previousMessages);
        if (totalTokens <= thresholdTokens) {
            return null;
        }
        int cutoff = findTurnCutoff(previousMessages);
        if (cutoff <= 0) {
            log.info("[compaction] 跳过：可切割轮次不足 keepTurns={}，估算tokens={} threadId={}",
                keepTurns, totalTokens, config.threadId().orElse(""));
            return null;
        }
        return null; // TODO(task2/task3)：切割与摘要在后续任务实现
    }

    /** 估算口径：移植 SAA TokenCounter.approximateMsgCounter（含 ToolResponse 数据与 toolCall arguments） */
    int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    total += response.responseData().length() / charsPerToken;
                }
            } else if (msg instanceof AssistantMessage assistantMessage) {
                if (msg.getText() != null) {
                    total += msg.getText().length() / charsPerToken;
                }
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    total += toolCall.arguments().length() / charsPerToken;
                }
            } else if (msg.getText() != null) {
                total += msg.getText().length() / charsPerToken;
            }
        }
        return total;
    }

    /** 切割点 = 倒数第 keepTurns 个 UserMessage 下标；Task 2 实现 */
    int findTurnCutoff(List<Message> messages) {
        return 0;
    }

    @Override
    public String getName() {
        return "SummarizingHook";
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of();
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl agent test -Dtest=SummarizingModelHookTest`
Expected: PASS（2 tests）

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/java/com/linagent/agent/compaction/ agent/src/test/java/com/linagent/agent/compaction/
git commit -m "feat(compaction): SummarizingModelHook 骨架——token 估算(chars/N 口径)与阈值放行 + Sink 接口预留"
```

---

### Task 2: turn 边界切割 + 工具配对保护 + 首条 UserMessage 保留

**Files:**
- Modify: `agent/src/main/java/com/linagent/agent/compaction/SummarizingModelHook.java`（`findTurnCutoff` 实现 + 配对保护）
- Test: `agent/src/test/java/com/linagent/agent/compaction/SummarizingModelHookTest.java`

**Interfaces:**
- Consumes: Task 1 的 `findTurnCutoff` 占位签名
- Produces: `int findTurnCutoff(List<Message>)`（0=不足 K 轮；>0=切割下标，含配对回退）；`boolean isSafeCutoffPoint(List<Message>, int)`（包可见，Task 3 不依赖但测试直测）

- [ ] **Step 1: 写失败测试（追加到 SummarizingModelHookTest）**

```java
    // ===== Task 2：切割 =====

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
        org.springframework.ai.chat.messages.ToolResponseMessage toolResp =
            new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call-1", "R")));
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
    void isSafeCutoffPointDirectCheck() {
        org.springframework.ai.chat.messages.AssistantMessage withCalls =
            AssistantMessage.builder().content("").properties(java.util.Map.of())
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "t", "{}")))
                .build();
        org.springframework.ai.chat.messages.ToolResponseMessage toolResp =
            new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("c1", "R")));
        SummarizingModelHook h = hook(1, 1);
        List<Message> pair = List.of(withCalls, toolResp);
        // 切割点 k 语义：[0,k) 被摘、[k,*) 保留。pair 中 withCalls=0、toolResp=1：
        assertThat(h.isSafeCutoffPoint(pair, 0)).isTrue();  // 两者都在保留侧（同侧安全）
        assertThat(h.isSafeCutoffPoint(pair, 1)).isFalse(); // withCalls 被摘、toolResp 保留 → 孤儿 ToolResponse
        assertThat(h.isSafeCutoffPoint(pair, 2)).isTrue();  // 两者都被摘（同侧安全）
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=SummarizingModelHookTest`
Expected: 新增 4 个测试 FAIL（findTurnCutoff 恒返 0 / isSafeCutoffPoint 不存在编译错——先加空方法使编译过再验证 FAIL）

- [ ] **Step 3: 实现（移植官方 SummarizationHook 的配对保护）**

```java
    private static final int SEARCH_RANGE_FOR_TOOL_PAIRS = 5;

    /** 切割点 = 倒数第 keepTurns 个 UserMessage 的下标；配对不安全时向左回退；不足 K 轮返回 0 */
    int findTurnCutoff(List<Message> messages) {
        int turnStarts = 0;
        int cutoff = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                turnStarts++;
                if (turnStarts == keepTurns) {
                    cutoff = i;
                    break;
                }
            }
        }
        if (cutoff < 0) {
            return 0;
        }
        while (cutoff > 0 && !isSafeCutoffPoint(messages, cutoff)) {
            cutoff--;
        }
        return cutoff;
    }

    /** 切割点 k：[0,k) 被摘、[k,*) 保留；不得使 AssistantMessage(toolCalls) 与其 ToolResponseMessage 分居两侧 */
    boolean isSafeCutoffPoint(List<Message> messages, int cutoffIndex) {
        if (cutoffIndex >= messages.size()) {
            return true;
        }
        int searchStart = Math.max(0, cutoffIndex - SEARCH_RANGE_FOR_TOOL_PAIRS);
        int searchEnd = Math.min(messages.size(), cutoffIndex + SEARCH_RANGE_FOR_TOOL_PAIRS);
        for (int i = searchStart; i < searchEnd; i++) {
            Message msg = messages.get(i);
            if (!(msg instanceof AssistantMessage assistantMessage) || assistantMessage.getToolCalls().isEmpty()) {
                continue;
            }
            java.util.Set<String> toolCallIds = new java.util.HashSet<>();
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                toolCallIds.add(toolCall.id());
            }
            for (int j = i + 1; j < messages.size(); j++) {
                if (messages.get(j) instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        if (toolCallIds.contains(response.id())) {
                            boolean aiBefore = i < cutoffIndex;
                            boolean toolBefore = j < cutoffIndex;
                            if (aiBefore != toolBefore) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl agent test -Dtest=SummarizingModelHookTest`
Expected: PASS（6 tests）

- [ ] **Step 5: Commit**

```bash
git add agent/src
git commit -m "feat(compaction): turn 边界切割 + 工具配对回退保护（移植官方 SummarizationHook 语义）"
```

---

### Task 3: cache-safe 摘要调用 + 容错 + Sink 通知 + 结果组装

**Files:**
- Modify: `agent/src/main/java/com/linagent/agent/compaction/SummarizingModelHook.java`（`compact()` 完整实现 + `summarize()`）
- Create: `agent/src/main/java/com/linagent/agent/compaction/LoggingSummarySink.java`
- Test: `agent/src/test/java/com/linagent/agent/compaction/SummarizingModelHookTest.java`

**Interfaces:**
- Consumes: Task 1 `SUMMARY_PREFIX`/`COMPACT_INSTRUCTION`/`CompactionSummarySink.SummaryContext`、Task 2 `findTurnCutoff`
- Produces: `compact()` 完整语义（结构 `[SystemMessage(SUMMARY_PREFIX+摘要), 首条UserMessage?, ...保留区]`）；`LoggingSummarySink`（@Component，Task 4 装配用）

- [ ] **Step 1: 写失败测试（追加）**

```java
    // ===== Task 3：摘要调用与结果组装 =====

    private org.springframework.ai.chat.model.ChatResponse summaryResponse(String text) {
        org.springframework.ai.chat.messages.AssistantMessage msg =
            org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content(text).properties(java.util.Map.of()).build();
        return new org.springframework.ai.chat.model.ChatResponse(
            List.of(new org.springframework.ai.chat.model.Generation(msg)));
    }

    @Test
    void compactReusesPrefixAndAppendsInstruction() {
        // 30 轮，keepTurns=20 → toSummarize = [a0, u1, a1, ..., u4, a4]（u0 为首条 user 被排除）
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
        // 摘要请求：前 N-1 条与 toSummarize 原样同引用（cache-safe 前缀），末条为指令 UserMessage
        List<Message> req = captured.get(0).getInstructions();
        assertThat(req.get(req.size() - 1).getText()).isEqualTo(SummarizingModelHook.COMPACT_INSTRUCTION);
        assertThat(req.subList(0, req.size() - 1))
            .containsExactlyElementsOf(msgs.subList(1, 10)); // a0..a4（u0 排除后 [1,10) 段）
        // 结果结构：[Sys(摘要), 首条u0, ...保留区(u10..)]
        assertThat(result.get(0)).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(result.get(0).getText()).startsWith(SummarizingModelHook.SUMMARY_PREFIX)
            .contains("压缩后的摘要正文");
        assertThat(result.get(1).getText()).startsWith("u0");
        assertThat(result.subList(2, result.size()))
            .containsExactlyElementsOf(msgs.subList(10, msgs.size()));
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
        SummarizingModelHook quick = new SummarizingModelHook(model, ctx -> {}, 1, 20, 2,
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
        // 旧摘要进入了摘要请求（被合并而非丢弃）
        org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        org.mockito.Mockito.verify(model).call(captor.capture());
        assertThat(captor.getValue().getInstructions().get(0).getText()).contains("旧摘要内容");
    }

    @Test
    void sinkNotifiedWithContext() {
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenReturn(summaryResponse("S"));
        List<java.util.List<Object>> received = new java.util.ArrayList<>();
        SummarizingModelHook h = new SummarizingModelHook(model, ctx -> received.add(List.of(
            ctx.threadId(), ctx.summary(), ctx.messagesBefore(), ctx.messagesAfter())), 1, 20, 2,
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
        assertThat((int) received.get(0).get(3)).isEqualTo(52);   // 摘要后 1+1+50
    }
```

注意测试类需补 import：`static org.mockito.ArgumentMatchers.any;`、`static org.mockito.Mockito.when;`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=SummarizingModelHookTest`
Expected: 新增 4 个测试 FAIL（compact() 切割后仍返回 null）

- [ ] **Step 3: 实现 compact() 完整逻辑 + summarize() + LoggingSummarySink**

`compact()` 的 `return null; // TODO` 段替换为：

```java
        UserMessage firstUser = null;
        for (Message m : previousMessages) {
            if (m instanceof UserMessage u) {
                firstUser = u;
                break;
            }
        }
        List<Message> toSummarize = new java.util.ArrayList<>();
        for (int i = 0; i < cutoff; i++) {
            if (previousMessages.get(i) != firstUser) {
                toSummarize.add(previousMessages.get(i));
            }
        }
        if (toSummarize.isEmpty()) {
            return null;
        }

        int messagesBefore = previousMessages.size();
        long start = System.currentTimeMillis();
        String summary = summarize(toSummarize, config);
        if (summary == null || summary.isBlank()) {
            return null; // 宁可超长不丢记忆
        }

        List<Message> newMessages = new java.util.ArrayList<>();
        newMessages.add(new org.springframework.ai.chat.messages.SystemMessage(SUMMARY_PREFIX + summary));
        if (firstUser != null && previousMessages.indexOf(firstUser) < cutoff) {
            newMessages.add(firstUser);
        }
        newMessages.addAll(previousMessages.subList(cutoff, previousMessages.size()));

        log.info("[compaction] threadId={} 估算tokens={} 阈值={} 消息 {}→{} 摘要耗时={}ms summaryChars={}",
            config.threadId().orElse(""), totalTokens, thresholdTokens, messagesBefore,
            newMessages.size(), System.currentTimeMillis() - start, summary.length());
        summarySink.onSummary(new CompactionSummarySink.SummaryContext(
            config.threadId().orElse(""), summary, messagesBefore, newMessages.size()));
        return newMessages;
```

`summarize()` 方法（新增，含 usage 观测——spec §6）：

```java
    /** cache-safe 摘要：原消息（字节原样前缀）+ 尾部压缩指令；失败/超时返回 null */
    private String summarize(List<Message> toSummarize, RunnableConfig config) {
        List<Message> request = new java.util.ArrayList<>(toSummarize);
        request.add(new org.springframework.ai.chat.messages.UserMessage(COMPACT_INSTRUCTION));
        try {
            org.springframework.ai.chat.model.ChatResponse resp = reactor.core.publisher.Mono
                .fromCallable(() -> chatModel.call(new org.springframework.ai.chat.prompt.Prompt(request)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .block(summaryTimeout);
            if (resp != null && resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                log.info("[compaction] 摘要调用 usage={}（验证 cache-safe 命中看 cached/prompt 比值）",
                    resp.getMetadata().getUsage());
            }
            return resp == null ? null : resp.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[compaction] 摘要失败（本轮跳过，下轮重试）threadId={} 原因={}",
                config.threadId().orElse(""), e.getMessage());
            return null;
        }
    }
```

`LoggingSummarySink.java`：

```java
package com.linagent.agent.compaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** MVP 默认实现：仅日志。跨会话任务接力在此扩展（spec §5.3 预留）。 */
@Component
public class LoggingSummarySink implements CompactionSummarySink {

    private static final Logger log = LoggerFactory.getLogger(LoggingSummarySink.class);

    @Override
    public void onSummary(SummaryContext ctx) {
        log.info("[compaction] summary produced: threadId={} messages {}→{} summaryChars={}",
            ctx.threadId(), ctx.messagesBefore(), ctx.messagesAfter(), ctx.summary().length());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl agent test -Dtest=SummarizingModelHookTest`
Expected: PASS（10 tests）

- [ ] **Step 5: Commit**

```bash
git add agent/src
git commit -m "feat(compaction): cache-safe 摘要调用（前缀复用+尾部指令）、失败/超时放行、Sink 通知与结果组装"
```

---

### Task 4: Spring 装配 + AgentFacade 纯化 + CompactionService 删除 + 配置

**Files:**
- Modify: `agent/src/main/java/com/linagent/agent/compaction/SummarizingModelHook.java`（加 @Component + @Value 构造装配）
- Modify: `agent/src/main/java/com/linagent/agent/agent/AgentFactory.java:42-52, 88-95`
- Modify: `agent/src/main/java/com/linagent/agent/facade/AgentFacade.java:48-59, 73-77, 112-126`
- Delete: `agent/src/main/java/com/linagent/agent/compaction/CompactionService.java`、`agent/src/test/java/com/linagent/agent/compaction/CompactionServiceTest.java`
- Modify: `web/src/main/java/com/linagent/web/controller/ConversationController.java:49-51`
- Modify: `web/src/main/resources/application.yml:23-24`
- Modify: `agent/src/test/java/com/linagent/agent/facade/AgentFacadeTest.java`、`agent/src/test/java/com/linagent/agent/agent/AgentFactoryTest.java`、`web/src/test/java/com/linagent/web/e2e/AgentEndToEndTest.java`

**Interfaces:**
- Consumes: Task 3 的 `SummarizingModelHook`/`LoggingSummarySink`
- Produces: `AgentFactory` 构造器新增第 7 参 `SummarizingModelHook summarizingHook`（现有 6 参之后追加）；`AgentFacade` 构造器去掉 `CompactionService`（变为 `(AgentFactory, ConversationRepository, TurnRepository, MessageRepository, String model)`）；agent 模块不再存在 `CompactionService` 类

- [ ] **Step 1: 先改测试（红）——AgentFacadeTest**

改动点（`agent/src/test/java/com/linagent/agent/facade/AgentFacadeTest.java`）：
1. 删 import `com.linagent.agent.compaction.CompactionService` 与字段 `compaction`、setUp 里 61-62 行的 mock 两行
2. 82 行构造改为 `facade = new AgentFacade(factory, conversations, turns, messages, "step-3.7-flash");`
3. 删除测试 `chatRunsCompactionAndSwitchesToNewThreadWithSummaryPrefix`（278 行起）与 `chatWithExistingCompactSummaryAlwaysPrependsSummarySystemMessage`（313 行起）
4. `chatWithoutSummarySendsPlainUserMessage`（334 行起）改写为**无论 compactSummary 有无都只传 UserMessage**（根治断言）：

```java
    @Test
    void chatAlwaysSendsPlainUserMessageRegardlessOfLegacySummary() throws com.alibaba.cloud.ai.graph.exception.GraphRunnerException {
        Conversation conv = conversations.seed("遗留摘要正文");
        facade.chat(conv.id(), "你好");
        // hook 化后 AgentFacade 恒只传当前 UserMessage（历史由 checkpoint 恢复；
        // 旧 compact_summary 列不再参与输入——根治重复注入）
        verify(capturedAgent).stream(any(UserMessage.class), any(RunnableConfig.class));
        verify(capturedAgent, never()).stream(any(List.class), any(RunnableConfig.class));
    }
```

   同时给 InMemoryConversationRepository 加 seed 辅助（或直接在测试里 save 一条带摘要的 Conversation，字段沿用现有构造——`new Conversation(id, "t", "conv-"+id, "遗留摘要正文", 0, "default", "linmj", Instant.now(), Instant.now())`，与 InMemory double 的过滤语义匹配）。setUp 里 73-77 行的 `stream(any(List.class), ...)` 打桩**删除**（List 重载不再被调用）。
5. 补 import：`static org.mockito.Mockito.never;`
6. 新增展示层零变化测试（spec §7 集成断言的 facade 层落点——hook 化后展示存储只增不删）：

```java
    @Test
    void displayStorageOnlyAppendsNeverRemoves() {
        Conversation conv = conversations.seed(null);
        List<Integer> sizes = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            facade.chat(conv.id(), "第" + i + "轮").blockLast();
            sizes.add(messages.data().size()); // InMemory double 的内部集合（按其字段名调整）
        }
        // 单调递增且每轮都有新增：压缩只发生在 checkpoint 层（hook），turn/message 永不回删
        assertThat(sizes).isSorted();
        assertThat(sizes.get(sizes.size() - 1)).isGreaterThan(sizes.get(0));
    }
```

   （`seedDefault`/`data()` 为 InMemory double 的辅助访问——若无则按现有 double 字段直取；核心断言：三轮后行数单调增。）

- [ ] **Step 2: 跑 AgentFacadeTest 确认编译失败（构造器不匹配）**

Run: `mvn -pl agent test -Dtest=AgentFacadeTest`
Expected: 编译错误 `AgentFacade` 构造器签名不含 CompactionService 移除后的形态

- [ ] **Step 3: 实现主代码**

`SummarizingModelHook` 头部加 Spring 装配（构造器加 @Value，类加 @Component）：

```java
@Component
public class SummarizingModelHook extends MessagesModelHook {
    // ...
    public SummarizingModelHook(ChatModel chatModel, CompactionSummarySink summarySink,
                                @org.springframework.beans.factory.annotation.Value("${agent.compaction.threshold-tokens:48000}") int thresholdTokens,
                                @org.springframework.beans.factory.annotation.Value("${agent.compaction.keep-turns:20}") int keepTurns,
                                @org.springframework.beans.factory.annotation.Value("${agent.compaction.chars-per-token:2}") int charsPerToken,
                                @org.springframework.beans.factory.annotation.Value("${agent.compaction.summary-timeout-seconds:60}") long summaryTimeoutSeconds) {
        this(chatModel, summarySink, thresholdTokens, keepTurns, charsPerToken,
            Duration.ofSeconds(summaryTimeoutSeconds));
    }

    // 原六参构造器保留（单测直用）
```

`AgentFactory.java`：
1. 字段/构造器加 `SummarizingModelHook summarizingHook`（第 8 参）
2. create 内 `hooks(List.of(skillsHook, shellHook))` → `hooks(List.of(skillsHook, shellHook, summarizingHook))`

`AgentFacade.java`：
1. 删 import `CompactionService`、字段、构造参数
2. defer 内 73-75 行压缩调用段替换为：

```java
            // 历史由 checkpoint 恢复（AppendStrategy 合并当前输入）；压缩在
            // SummarizingModelHook（BEFORE_MODEL）按真实消息触发，facade 不再预压缩
```

3. 112-126 行注入分支替换为：

```java
            // agent.stream 声明受检 GraphRunnerException，在 defer 内转 Flux.error 走统一错误路径；
            // 恒只传当前 UserMessage（spec §4：根治摘要重复注入）
            Flux<NodeOutput> nodeOutputs;
            try {
                nodeOutputs = handle.agent().stream(new UserMessage(content), config);
            } catch (GraphRunnerException e) {
                nodeOutputs = Flux.error(e);
            }
```

   并删 `SystemMessage`/`List` 相关 import（若仅此处使用）。

`CompactionService.java` + `CompactionServiceTest.java`：`git rm` 删除。

`ConversationController.java:49-51`：`first.compactSummary(), first.compactedTurnSeq()` → `null, null`（注释一行：摘要与锚点已由 hook 体系接管，列停用）。

`application.yml:23-24`：

```yaml
  compaction:
    threshold-tokens: 48000
    keep-turns: 20
    summary-timeout-seconds: 60
    chars-per-token: 2
```

- [ ] **Step 4: 修复受影响测试（AgentFactoryTest / AgentEndToEndTest）**

`AgentFactoryTest`：`new AgentFactory(...)` 调用补第 8 参 `new SummarizingModelHook(mock(ChatModel.class), ctx -> {}, 1_000_000, 20, 2, Duration.ofSeconds(60))`（大阈值=不触发）。文件头部按需补 import。

`web/src/test/java/com/linagent/web/e2e/AgentEndToEndTest.java`：删 `@MockBean CompactionService` 字段与 `when(...compactIfNeeded...)` 打桩行及 import（Spring 自动装配新 AgentFacade 构造）。

先 `mvn -pl agent install -DskipTests`（agent 改动落 jar，否则 web classpath 用旧类），再跑：

Run: `mvn -pl agent test -Dtest='AgentFacadeTest,AgentFactoryTest,SummarizingModelHookTest' && mvn -pl web test -Dtest=AgentEndToEndTest`
Expected: 全 PASS

- [ ] **Step 5: 全模块回归**

Run: `mvn test`
Expected: 全 PASS（含 web 全量；FlywayMigrationTest 不受影响——DDL 未动）

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(compaction)!: hook 挂载 + AgentFacade 纯化只传 UserMessage + 删除 CompactionService 换代体系"
```

---

### Task 5: 集成测试——REPLACE 落库与根治终验（Testcontainers PG + 真实 ReactAgent）

**Files:**
- Test: `agent/src/test/java/com/linagent/agent/compaction/CompactionHookIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 的 `SummarizingModelHook`；`CheckpointRestartTest` 的容器/RecordingModel 模式（`agent/src/test/java/com/linagent/agent/persistence/CheckpointRestartTest.java:41-162`）
- Produces: 无（验收测试）

- [ ] **Step 1: 写集成测试**

```java
package com.linagent.agent.compaction;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根治终验（spec §7）：真实 ReactAgent + PostgresSaver + 低阈值 hook 连续三轮，
 * 每轮模型输入中「此前对话摘要：」SystemMessage 恰好 1 份（旧实现逐轮叠加），
 * 且消息条数随 REPLACE 下降。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactionHookIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;

    private final List<List<Message>> streamedPrompts = new CopyOnWriteArrayList<>();

    @BeforeAll
    void initDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        dataSource = ds;
    }

    @Test
    void compactionReplacesHistoryAndKeepsSingleSummaryAcrossTurns() throws Exception {
        BaseCheckpointSaver saver = PostgresSaver.builder().datasource(dataSource).build();
        String threadId = "compact-" + System.nanoTime();

        SummarizingModelHook hook = new SummarizingModelHook(
            new RecordingModel(), ctx -> {}, 5, 2, 2, Duration.ofSeconds(60)); // 阈值 5 tokens：必触发；保 2 轮

        ReactAgent agent = ReactAgent.builder()
            .name("compact-test-agent")
            .model(new RecordingModel())
            .systemPrompt("你是测试助手")
            .hooks(List.of(hook))
            .saver(saver)
            .build();

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        for (int round = 1; round <= 3; round++) {
            agent.stream(new UserMessage("第" + round + "轮问题，" + "x".repeat(60)), config).blockLast();
        }

        // 每一轮的 stream Prompt：摘要 SystemMessage 恰 1 份、无叠加
        assertThat(streamedPrompts).hasSizeGreaterThanOrEqualTo(3);
        for (List<Message> prompt : streamedPrompts) {
            long summaryCount = prompt.stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
                .filter(m -> m.getText() != null && m.getText().startsWith(SummarizingModelHook.SUMMARY_PREFIX))
                .count();
            assertThat(summaryCount).as("摘要 SystemMessage 应恰 1 份，实际 %d：%s", summaryCount, prompt).isEqualTo(1);
        }
        // 历史 REPLACE 生效：最后一轮消息条数明显小于无压缩累积（60+ 字符/轮文本 × 3 轮仅保 2 轮）
        List<Message> last = streamedPrompts.get(streamedPrompts.size() - 1);
        assertThat(last.size()).isLessThan(12);
    }

    /** 记录 stream/call 收到的消息；call（摘要调用）返回固定摘要文本 */
    class RecordingModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return respond("集成摘要：用户连续提问并得到回答。");
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            streamedPrompts.add(new ArrayList<>(prompt.getInstructions()));
            return reactor.core.publisher.Flux.just(respond("好的"));
        }

        private ChatResponse respond(String text) {
            AssistantMessage msg = AssistantMessage.builder().content(text).properties(Map.of()).build();
            return new ChatResponse(List.of(new Generation(msg)));
        }
    }
}
```

（若 `streamedPrompts` 因 SAA 轮内多次模型调用收集到多于 3 个 Prompt，断言语义不变——每个 Prompt 都必须恰 1 份摘要；`hooks(List.of(hook))` 的 builder 方法若签名不符，以 AgentFactory 现行挂载写法为准。）

- [ ] **Step 2: 跑集成测试**

Run: `mvn -pl agent test -Dtest=CompactionHookIntegrationTest`
Expected: PASS（首次跑会拉 postgres:16-alpine 镜像）

- [ ] **Step 3: Commit**

```bash
git add agent/src/test
git commit -m "test(compaction): 集成终验——真实 saver/agent 三轮 REPLACE 单份摘要（根治重复注入）"
```

---

### Task 6: 文档收尾 + 全量回归

**Files:**
- Modify: `CLAUDE.md`（「持久化分工」压缩段、「架构」节相关描述）
- Modify: `docs/superpowers/specs/2026-09-22-compaction-hook-design.md`（状态行）

**Interfaces:** 无

- [ ] **Step 1: 更新 CLAUDE.md**

「持久化分工（容易搞混）」一节的压缩条目替换为：

```markdown
- 压缩：`SummarizingModelHook`（BEFORE_MODEL，AgentFactory 挂载）对**真实 state messages**
  估算 token（`agent.compaction.chars-per-token`，默认 2），超 `threshold-tokens` 时保最近
  `keep-turns`（默认 20）个完整 turn + 首条 UserMessage，其余经 cache-safe 调用（原消息前缀
  + 尾部压缩指令）生成摘要，`UpdatePolicy.REPLACE` 原地替换——**不换 threadId、不碰展示存储**；
  失败/超时原样放行。threadId 恒为 `conv-{id}`；`compact_summary`/`compacted_turn_seq` 列停用
  （PO 字段保留，创建传 null）；`CompactionSummarySink` 为跨会话接力预留（MVP LoggingSummarySink）。
  已实证坑：`AgentCommand.getMessages()` 包私有——hook 核心逻辑须收在包可见 `compact()` 供单测。
```

同时检查全文其他提及 CompactionService/threadId 换代处（`rg -n "CompactionService|换代|compact_summary" CLAUDE.md`）一并更新；「已知边界」等无涉不动。

- [ ] **Step 2: 更新 spec 状态**

`docs/superpowers/specs/2026-09-22-compaction-hook-design.md` 头部状态行改为 `- 状态：已实施（feat/compaction-hook，实施计划 docs/superpowers/plans/2026-09-22-compaction-hook.md）`。

- [ ] **Step 3: 全量回归**

Run: `mvn test && cd frontend && npx vitest run`
Expected: 后端全 PASS；前端不涉及（无 API 变化）全 PASS

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/
git commit -m "docs: CLAUDE.md 压缩机制改写为 hook 体系 + spec 状态已实施"
```
