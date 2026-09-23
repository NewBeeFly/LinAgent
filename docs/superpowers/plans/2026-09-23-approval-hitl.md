# 工具审批（HITL Approval）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 命令级工具审批——write_file/shell 调用按白名单+双作用域规则判定，未授权则中断（checkpoint 持久化），前端卡片决策后 resume 续流。

**Architecture:** 自写 `ApprovalHook`（抄 SAA `HumanInTheLoopHook` 骨架，判定层换 `PermissionRuleEngine`）+ SAA 原生中断（`InterruptableAction`）与恢复（`RunnableConfig.addMetadata(HUMAN_FEEDBACK_METADATA_KEY, InterruptionMetadata)`）机制；`ApprovalRequest` 为流尾事件（不发 TurnDone）；TOOL_CALL 中断时提前落库；协议层 chat/approvals 双端点分流（409 模态）。

**Tech Stack:** SAA 1.1.2.3（InterruptableAction/InterruptionMetadata/HumanInTheLoopHook 骨架）、Spring AI 1.1.2、SD JDBC + Flyway V6、JUnit5/Mockito/AssertJ/Testcontainers、Vue3+Vite。

**Spec:** `docs/superpowers/specs/2026-09-23-approval-hitl-design.md`（含全部调研实证与决策记录，实施前必读）

## Global Constraints

- 分支：`feat/approval-hitl`（Task 1 创建）
- SAA 关键签名（本会话源码实证）：`InterruptableAction.interrupt(nodeId, state, config)` 返回 `Optional<InterruptionMetadata>`；`InterruptionMetadata.builder(hookName, state).addToolFeedback(ToolFeedback.builder().id().name().description().arguments().build()).addToolsAutomaticallyApproved(toolCall).build()`；feedback 通道 `RunnableConfig.builder().addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, metadata)`；hook 的 `afterModel` 处理 feedback（REJECTED 生成内置拒绝文案的 ToolResponse 并替换原 AssistantMessage）
- `AgentEvent` 是 sealed interface（7 record）——新增 record 必须 同步改 `SseEventMapper`（sealed switch 编译器会强制提示）
- `Turn` PO 用工厂方法模式（`Turn.running/complete/fail`）——新状态 `WAITING_APPROVAL` 加同款工厂
- `SegmentBuffer.recordToolCall(callId, toolName, arguments)`（synchronized）可复用于 TOOL_CALL 提前落库；**锁序红线：sink → buffer，无反向嵌套**
- 规则匹配语义（spec §5）：`*` 只做空格分隔段匹配；复合命令按 `&&/||/;/|` 拆段**全部命中才放行**；评估序白名单→session→user→审批
- 展示存储约定延续：TOOL_CALL/TOOL_RESULT 各一行以 call_id 关联；turn/message 表不回删
- Java 驼峰；PO record + @Table 放 `persistence/po/`；SQL 只许 @Query 注解与 Flyway 文件
- 每任务收尾：该任务测试绿 + `mvn -pl agent install -DskipTests`（agent 改动后 web 才能用新类）
- @WebMvcTest 切片：新增仓库接口后各切片测试补 @MockBean（CLAUDE.md 红线）

---

### Task 1: 分支 + SAA 中断/恢复 Spike（探测性集成测试）

**Files:**
- Create: `agent/src/test/java/com/linagent/agent/approval/SaaInterruptionSpikeTest.java`

**Interfaces:**
- Consumes: SAA `HumanInTheLoopHook`（原生工具级，spike 只验机制不做命令级）、`PostgresSaver`、`ReactAgent`
- Produces: **形态结论**（记录在测试类 javadoc + 断言里）：① 中断时 `Flux<NodeOutput>` 的信号形态 ② resume 的准确调用方式 ③ REJECTED 时工具是否真实执行（源码疑点）。后续 Task 4/5/6 的设计依据；若 SAA 缺口 → 退方案 C（spec §3），本计划 Task 5/6 改由执行时裁定（届时控制器下 ruling）

**性质说明**：这是 spike——断言按「预期形态」写，实施者以实测为准调整断言并把结论固化进 javadoc；产出是知识不是产品码。

- [ ] **Step 1: 创建分支**

```bash
git checkout -b feat/approval-hitl
```

- [ ] **Step 2: 写 spike 测试**

```java
package com.linagent.agent.approval;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.nodes.NodeOutput; // 若包名不符以 sources jar 实测为准
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPIKE：钉死 SAA 1.1.2.3 中断/恢复三件事（结论写回本注释，Task 4/5/6 依据）：
 * A. 中断时 Flux<NodeOutput> 的信号形态（最后一个 NodeOutput 是什么？流如何 complete？）
 * B. resume 准确方式：同 threadId + addMetadata(HUMAN_FEEDBACK_METADATA_KEY, ...) + stream(?, config)
 * C. REJECTED 的 toolCall 是否真实执行（hook afterModel 把 REJECTED 也保留在 newToolCalls——
 *    工具节点是否会因已有 ToolResponse 而跳过？实测断言见 rejectPath）
 *
 * 【实施者按实测填写结论】
 * A: ...
 * B: ...
 * C: ...
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SaaInterruptionSpikeTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;
    private final List<String> toolExecutions = new CopyOnWriteArrayList<>();

    /** 受控模型：第 1 次调用强制发起 echo_tool 调用，之后返回纯文本 */
    static class ScriptedModel implements ChatModel {
        int calls = 0;
        @Override public ChatResponse call(Prompt prompt) {
            calls++;
            AssistantMessage msg = (calls == 1)
                ? AssistantMessage.builder().content("").properties(Map.of())
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "echo_tool", "{\"text\":\"hi\"}")))
                    .build()
                : AssistantMessage.builder().content("完成").properties(Map.of()).build();
            return new ChatResponse(List.of(new Generation(msg)));
        }
        @Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    /** 被 approvalOn 命中的测试工具 */
    class EchoTool {
        @Tool(name = "echo_tool", description = "回显文本（spike 用）")
        public String echo(String text) { toolExecutions.add(text); return "echo:" + text; }
    }

    @BeforeAll
    void init() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(pg.getJdbcUrl()); ds.setUser(pg.getUsername()); ds.setPassword(pg.getPassword());
        dataSource = ds;
    }

    @Test
    void interruptAndResumeApprovePath() throws Exception {
        BaseCheckpointSaver saver = PostgresSaver.builder().datasource(dataSource).build();
        ScriptedModel model = new ScriptedModel();
        HumanInTheLoopHook hook = HumanInTheLoopHook.builder()
            .approvalOn("echo_tool", "spike 审批").build();
        ReactAgent agent = ReactAgent.builder()
            .name("spike-agent").model(model).systemPrompt("你是测试助手")
            .tools(new EchoTool()).hooks(List.of(hook)).saver(saver).build();

        String threadId = "spike-" + System.nanoTime();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        // 第一段：跑至中断。观察点 A：lastOutputs 的实际内容（打断点/打印确认后固化断言）
        List<com.alibaba.cloud.ai.graph.NodeOutput> outputs = new CopyOnWriteArrayList<>();
        agent.stream(new UserMessage("跑工具"), config).doOnNext(outputs::add).blockLast();

        // 预期：未执行（中断在工具前）——若实测不同，按实测改写并记录
        assertThat(toolExecutions).isEmpty();

        // 第二段：带 APPROVED feedback resume。观察点 B：stream 第一参传什么（null? 新 UserMessage?）
        InterruptionMetadata feedback = InterruptionMetadata.builder("spike", null)
            .addToolFeedback(ToolFeedback.builder()
                .id("call-1").name("echo_tool").arguments("{\"text\":\"hi\"}")
                .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED).build())
            .build();
        RunnableConfig resumeConfig = RunnableConfig.builder().threadId(threadId)
            .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback).build();
        agent.stream(new UserMessage(""), resumeConfig).blockLast();

        assertThat(toolExecutions).containsExactly("hi"); // 批准 → 真实执行
    }

    @Test
    void rejectPath() throws Exception {
        // 与 approvePath 同构，feedback 换 REJECTED + description("测试拒绝理由")
        // 观察点 C 断言（二选一，按实测固化）：
        //   assertThat(toolExecutions).isEmpty();  // 拒绝 → 未执行
        //   assertThat(toolExecutions).hasSize(1); // 拒绝仍执行（记录为 SAA 行为，Task 4 需处理）
        // 同时断言第二次模型调用收到的消息里含 "rejected by human"（hook 内置拒绝文案）
        throw new UnsupportedOperationException("由实施者按 approvePath 复制改写并按实测固化断言");
    }
}
```

（实施提示：`InterruptionMetadata.builder` 第二参 state 允许 null 以 spike 简化——若构造器要求非空，从 `agent.getState(config)` 取；`NodeOutput` 包名以编译器提示为准。tools() 的参数形态若不接受对象，参照 `AgentFactory` 的 `MethodToolCallbackProvider` 路径。）

- [ ] **Step 3: 跑 spike，按实测调整断言并把 A/B/C 结论写回 javadoc**

Run: `mvn -pl agent test -Dtest=SaaInterruptionSpikeTest`
Expected: PASS（断言与实测对齐后）；结论三行填入类 javadoc

- [ ] **Step 4: Commit**

```bash
git add agent/src/test
git commit -m "test(approval): SAA 中断/恢复 spike——钉死 Flux 信号形态/resume 方式/REJECTED 执行语义"
```

---

### Task 2: V6 迁移 + PermissionRule PO + 仓库

**Files:**
- Create: `web/src/main/resources/db/migration/V6__permission_rule.sql`
- Create: `agent/src/main/java/com/linagent/agent/persistence/po/PermissionRule.java`
- Create: `agent/src/main/java/com/linagent/agent/persistence/repository/PermissionRuleRepository.java`
- Test: `agent/src/test/java/com/linagent/agent/persistence/repository/PermissionRuleRepositoryTest.java`（参照 `RepositoryTest` 的 Testcontainers 模式）

**Interfaces:**
- Produces: `PermissionRule(Long id, String tenantId, String userId, String toolName, String pattern, String effect, Instant createdAt)`（record + @Table("permission_rule")，工厂 `PermissionRule.allow(tenantId, userId, toolName, pattern)`）；仓库 `List<PermissionRule> findByTenantIdAndUserIdAndEffect(String tenantId, String userId, String effect)`（派生查询）、`void deleteByTenantIdAndUserIdAndId(String tenantId, String userId, Long id)`、`existsByTenantIdAndUserIdAndToolNameAndPattern(...)`（唯一约束预检）

- [ ] **Step 1: 写失败测试**（CRUD round-trip，参照 RepositoryTest 的 Testcontainers 模式）：

```java
@Test
void allowRuleRoundTripAndTenantIsolation() {
    PermissionRule saved = rules.save(
        PermissionRule.allow("default", "linmj", "shell", "pip install *"));
    assertThat(saved.effect()).isEqualTo("ALLOW");
    assertThat(rules.findByTenantIdAndUserIdAndEffect("default", "linmj", "ALLOW"))
        .extracting(PermissionRule::pattern).containsExactly("pip install *");
    // 跨租户/用户隔离
    assertThat(rules.findByTenantIdAndUserIdAndEffect("other", "linmj", "ALLOW")).isEmpty();
    assertThat(rules.findByTenantIdAndUserIdAndEffect("default", "someone", "ALLOW")).isEmpty();
    // 唯一约束预检
    assertThat(rules.existsByTenantIdAndUserIdAndToolNameAndPattern(
        "default", "linmj", "shell", "pip install *")).isTrue();
}
```
- [ ] **Step 2: 跑测试确认失败**（类不存在编译错）
- [ ] **Step 3: 实现**——V6 DDL 按 spec §5 逐字（BIGSERIAL 主键、UNIQUE 四列、effect 默认 'ALLOW' 由应用层保证）；PO 工厂方法；仓库接口（CrudRepository）
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 补切片 @MockBean**——`rg -l "@WebMvcTest" web/src/test` 的每个切片测试补 `@MockBean PermissionRuleRepository`
- [ ] **Step 6: Commit** `feat(approval): permission_rule 表 V6 + PO + 仓库（tenant/user 隔离）`

---

### Task 3: PermissionRuleEngine（匹配语义矩阵，TDD 重头）

**Files:**
- Create: `agent/src/main/java/com/linagent/agent/approval/PermissionRuleEngine.java`
- Test: `agent/src/test/java/com/linagent/agent/approval/PermissionRuleEngineTest.java`

**Interfaces:**
- Produces（Task 4/6 依赖，签名逐字）：
  - `public record Verdict(boolean needsApproval, List<PendingItem> items)`；`public record PendingItem(String callId, String toolName, String arguments, String payload, List<SubVerdict> subVerdicts, String suggestedRule)`；`public record SubVerdict(String segment, boolean allowed, String source)`（source ∈ `BUILTIN/session/user`）
  - `public Verdict evaluate(String toolName, String callId, String payload, ApprovalContext ctx)`
  - `public record ApprovalContext(String tenantId, String userId, Long conversationId)`（会话规则 key）
  - 规则输入：`List<PermissionRule> userRules`（调用方查库传入——引擎不做 IO）+ 内置 `SessionRuleStore`（见 Task 4，引擎只读接口 `Set<String> sessionPatterns(ApprovalContext ctx, String toolName)`——由构造器注入函数 `Function<ApprovalContext, Map<String, Set<String>>>`？**简化：引擎构造器收 `SessionRules` 接口实例**：`interface SessionRules { Set<String> patterns(String tenantId, String userId, Long conversationId, String toolName); void add(...); }`）
  - `public static String suggestPattern(String toolName, String payload)`：命令取前 2 个非选项 token + `" *"`（`pip install pandas` → `pip install *`）；单 token → `*`（工具级）；write_file 取路径父目录 + `/*`

- [ ] **Step 1: 写失败测试**（矩阵，每组一个 @Test）：

```java
// 段通配：allow("shell","pip install *") 命中 "pip install pandas"/"pip install -r req.txt"，不命中 "pip installx"
// 复合命令：allow "ls *" + "pip install *" → "ls && pip install pandas" 放行；"ls && rm -rf /" 整体 NEEDS_APPROVAL（subVerdicts 标明 rm 段未命中）
// 白名单："git status"/"cat x.txt" 无任何规则也放行（source=BUILTIN）；"git push" 不在白名单
// 评估序：session 命中即放行（不查 user）；两者都无 → needsApproval=true 且 items 含 suggestedRule
// 工具级 '*'：命中 shell 任意命令
// write_file：allow("write_file","reports/*") 命中 "reports/a.csv" 不命中 "data/a.csv"
// read 工具：read_file/list_dir/csv_summary/read_skill 恒放行（payload 任意）
// suggestPattern：三分支
// 空命令/空白 payload 边界
```

- [ ] **Step 2: 跑测试确认失败** → **Step 3: 实现**（白名单常量 `Set<String> READ_ONLY_COMMANDS` 按 spec §5 清单；拆段正则 `&&|\|\||;|\|`；前缀匹配 `payload.startsWith(pattern 去尾 " *") && 后续字符是空白或结尾`）→ **Step 4: 绿** → **Step 5: Commit** `feat(approval): 规则引擎——白名单/段通配/复合拆段/双作用域评估序/建议规则`

---

### Task 4: ApprovalHook（抄 SAA 骨架）+ SessionRules

**Files:**
- Create: `agent/src/main/java/com/linagent/agent/approval/SessionRules.java`（接口 + `InMemorySessionRules` @Component：`ConcurrentHashMap<String, Set<String>>`，key=`tenant:user:conv:tool`，含 `evict(conversationId)` 清理）
- Create: `agent/src/main/java/com/linagent/agent/approval/ApprovalHook.java`
- Test: `agent/src/test/java/com/linagent/agent/approval/ApprovalHookTest.java`

**Interfaces:**
- Consumes: Task 1 结论（中断/feedback 形态）、Task 3 `PermissionRuleEngine`/`Verdict`、SAA `HumanInTheLoopHook` 骨架（`interrupt()`/`afterModel()` 结构照抄，判定层换引擎）
- Produces: `ApprovalHook extends ModelHook implements AsyncNodeActionWithConfig, InterruptableAction`（构造器 `(PermissionRuleEngine engine)`，无状态可单例）；`interrupt(nodeId, state, config)` 内：取最后 AssistantMessage(toolCalls)（抄 `getLastAssistantMessage`，含「后跟 ToolResponseMessage 则不中断」防重逻辑）→ 逐 toolCall 调 `engine.evaluate`（payload：shell 取 arguments JSON 的 command 字段解析——**简化：整个 arguments 原文作为 payload 匹配**，命令在 JSON 里前缀匹配仍成立？不成立（`{"command":"pip install x"}` 前缀是 `{"command"...`）——**必须解析**：jackson 解 arguments 取第一个字符串字段值（shell=command、write_file=path，字段名映射常量）→ 全部 AUTO_ALLOW 返回 empty；任一 NEEDS_APPROVAL 构建 `InterruptionMetadata`（builder 抄 `buildInterruptionMetadata`：needsApproval 项 addToolFeedback(id/name/description=引擎明细渲染文本/arguments)，放行项 addToolsAutomaticallyApproved）→ 返回 Optional；`afterModel` 照抄 SAA 原生 feedback 处理（APPROVED/EDITED/REJECTED 三分支 + 拒绝文案）不改
- Produces（Task 5 消费的中断结果读取）：`static Verdict verdictFrom(InterruptionMetadata md)`——把 pending items 从 metadata 反解（供 facade 构建 ApprovalRequest 事件）

- [ ] **Step 1: 写失败测试**：
```java
// interrupt 判定：白名单命令+无 toolCalls → empty；shell 非白名单 → Optional 且 metadata.toolFeedbacks 含该 call、
//   description 含 subVerdicts 文本；混合（read_file 放行 + shell 需审）→ 只有 shell 进 feedback、read 进 automaticallyApproved
// afterModel（照抄验证）：APPROVED → 替换后 AssistantMessage 保留原 toolCalls；REJECTED → 生成含 "rejected by human" 的
//   ToolResponseMessage + description 回传；无 feedback metadata → 空 updates
// verdictFrom 反解 round-trip
```
（state 构造：`OverAllState` 建 builder 塞 "messages" 键 List<Message>——参照 SAA hook 源码用法；不确定处允许查 sources jar `/tmp/saa-src`）
- [ ] **Step 2: 红** → **Step 3: 实现**（骨架照抄 `/tmp/saa-src/.../hip/HumanInTheLoopHook.java`，替换判定段）→ **Step 4: 绿** → **Step 5: Commit** `feat(approval): ApprovalHook——引擎判定 + SAA 中断/feedback 骨架`

---

### Task 5: AgentFacade 中断路径（事件/落库/状态/TurnDone 跳过/409 前置）

**Files:**
- Modify: `agent/src/main/java/com/linagent/agent/facade/AgentEvent.java`（新增 record，sealed permits 同步）
- Modify: `agent/src/main/java/com/linagent/agent/facade/AgentFacade.java`
- Modify: `agent/src/main/java/com/linagent/agent/persistence/po/Turn.java`（`waitingApproval()` 工厂：status="WAITING_APPROVAL"，finishedAt=null）
- Modify: `agent/src/main/java/com/linagent/agent/agent/AgentFactory.java`（hooks 列表加 approvalHook）
- Modify: `web/src/main/java/com/linagent/web/.../SseEventMapper.java`（ApprovalRequest → SSE 分支）
- Test: `AgentFacadeTest` 扩展 + `SseEventMapperTest`（若存在；否则并入 facade 测试）

**Interfaces:**
- Produces: `record ApprovalRequest(Long turnId, Long conversationId, List<PendingItem> items) implements AgentEvent`（PendingItem 复用 Task 3 record，字段 JSON 序列化天然可回放）；`AgentFacade.chat()` 新前置：查当前会话是否存在 status=WAITING_APPROVAL 的 turn（`turns.existsByConversationIdAndStatus(id,"WAITING_APPROVAL")`——TurnRepository 补派生查询）→ 存在则抛 `ApprovalPendingException`（新异常类，web 层映射 409，Task 6 接）
- 中断检测：Task 1 结论 A 的信号形态在此消费——**以 spike 结论为准**实现分支：识别「流因中断收尾」（预计形态：最后 NodeOutput 的 state 含未决 toolCalls 或特定中断标记）→ 发 `AgentEvent.ApprovalRequest` → `buffer.flushAll()` 后 `turns.save(turn.waitingApproval())` → **跳过** concatWith 的 TurnDone（once-guard 同款：AtomicBoolean interrupted 在中断分支置位，TurnDone 段检查）→ `buffer.recordToolCall(callId, toolName, arguments)` 对每个 pending item 提前落 TOOL_CALL 行（在发 ApprovalRequest 前，保证卡片数据已持久）
- Cancel 路径交互：WAITING_APPROVAL turn 的取消（前端关闭会话等）→ `turn.fail("CANCELLED_WHILE_WAITING")`（复用现有 CANCEL 链路对 waiting 状态的处理，若无此链路则最小补：cancel 端点对 WAITING_APPROVAL turn 直接落 FAILED）

- [ ] **Step 1: 写失败测试**（AgentFacadeTest 现有桩模式扩展）：
```java
// chatWhenApprovalPendingReturns409：seed WAITING_APPROVAL turn → chat 抛 ApprovalPendingException
// interruptedTurnEmitsApprovalRequestAndSkipsTurnDone：桩 agent 主流发「中断形态」输出（按 spike 结论构造 NodeOutput）
//   → 断言事件流含 ApprovalRequest（items 完整）、无 TurnDone、turn 状态 WAITING_APPROVAL、
//   message 表出现 TOOL_CALL 行（arguments 原文）
// SseEventMapper：ApprovalRequest → SSE event 类型 "approval_request"（data JSON 含 items）
```
- [ ] **Step 2: 红** → **Step 3: 实现** → **Step 4: 绿 + `mvn -pl agent install -DskipTests` + web 测试** → **Step 5: Commit** `feat(approval): 中断路径——ApprovalRequest 流尾事件/TOOL_CALL 提前落库/WAITING_APPROVAL/TurnDone 跳过/409 前置`

---

### Task 6: approvals/permission-rules 端点 + resume

**Files:**
- Create: `web/src/main/java/com/linagent/web/controller/ApprovalController.java`
- Create: `web/src/main/java/com/linagent/web/controller/PermissionRuleController.java`
- Create: `web/src/main/java/com/linagent/web/dto/ApprovalDtos.java`（请求/响应 record）
- Modify: `agent/.../facade/AgentFacade.java`（`resume()`）+ GlobalExceptionHandler（ApprovalPendingException→409）
- Test: `web/src/test/.../ApprovalControllerTest.java`（@WebMvcTest，参照 ChatControllerSseTest 模式 + @MockBean 全仓库）

**Interfaces:**
- `POST /api/conversations/{id}/approvals` body：`record ApprovalRequest(String conversationId?, List<ItemDecision> items, String remember)`；`record ItemDecision(String callId, String decision /*approve|reject*/, String reason)`；remember ∈ `once|session|forever`
  - 语义：remember=forever → 对每个 approve 项 `PermissionRuleRepository.save(PermissionRule.allow(ctx, tool, suggestedPattern))`（suggestedPattern 由前端回传或服务端重算——**服务端重算**：engine.suggestPattern，前端可不管）；session → `InMemorySessionRules.add`；校验 pending 存在且 callId 集合匹配（幂等：不匹配→409）→ 构建 InterruptionMetadata（APPROVED/REJECTED+description=reason）→ `facade.resume(conversationId, metadata)` → 返回 SSE（`text/event-stream`，与 chat 同款）
- `GET /api/conversations/{id}/approvals`：无 pending → 204；有 → `record PendingApproval(Long turnId, List<PendingItem> items)`（从 message 表 pending TOOL_CALL 行 + `engine.evaluate` 重算 subVerdicts）
- `facade.resume(Long conversationId, InterruptionMetadata feedback) → Flux<AgentEvent>`：读会话 threadId（同 chat 的 requireOwned 预检）→ `RunnableConfig.builder().threadId(...).addMetadata(HUMAN_FEEDBACK_METADATA_KEY, feedback).build()` → `handle.agent().stream(resumeInput, config)`（resumeInput 按 spike 结论 B）→ 走与 chat 相同的事件映射链（含再次中断→再次 ApprovalRequest 的递归语义）→ 完成时 turn.complete
- `GET/POST/DELETE /api/permission-rules`：当前身份（AuthContextHolder）过滤的 CRUD；POST 校验 pattern 非空、tool ∈ {shell, write_file}

- [ ] **Step 1: 写失败测试**（409 幂等、resume 返回 SSE 流、forever 落库断言、session 不落库、GET pending 从 message 表构建、rules CRUD 权限隔离）→ **Step 2: 红** → **Step 3: 实现** → **Step 4: 绿** → **Step 5: Commit** `feat(approval): approvals 双端点 + remember 三档 + permission-rules CRUD + resume SSE`
- [ ] **Step 6: 端到端集成测试**（spec §11 第二条落点；agent 模块，Testcontainers PG + ScriptedModel 桩 + **AgentFactory 完整链**而非 mock ReactAgent）：

```java
@Test
void endToEndChatInterruptResumeApproveCompletesTurn() {
    // 桩模型第一轮发起 shell "pip install pandas"（非白名单）→ facade.chat() 流：
    //   断言收到 AgentEvent.ApprovalRequest（items[0].suggestedRule == "pip install *"）
    //   断言无 TurnDone、turn.status == WAITING_APPROVAL、message 表有该 TOOL_CALL 行
    // facade.resume(convId, approveAllMetadata) → 断言：工具真实执行（桩工具记录）、
    //   续流正常文本 → TurnDone(COMPLETED)、turn COMPLETED
// 复用 SaaInterruptionSpikeTest 的容器/桩模式；facade 依赖的仓库用 Testcontainers 直连
}
// 对照组：resume(reject+reason) → 断言模型第二次调用输入含 "rejected by human" 且工具未执行、turn COMPLETED
```

Run: `mvn -pl agent test -Dtest=ApprovalEndToEndTest` → PASS → **Step 7: Commit**（amend 进 Step 5 的 commit）

---

### Task 7: 前端（卡片/横幅/turn.ts/设置页）

**Files:**
- Create: `frontend/src/components/ApprovalCard.vue`
- Create: `frontend/src/views/PermissionSettings.vue` + 路由注册
- Modify: `frontend/src/lib/turn.ts`（ApprovalRequest 分区 + 两段流合并）、聊天主组件（卡片渲染位 + 409 横幅）、`api.ts`（新端点）

**Interfaces:**
- Consumes: SSE `approval_request` 事件（data JSON：`{turnId, conversationId, items:[{callId, toolName, arguments, subVerdicts, suggestedRule}]}`）、REST 端点（Task 6）
- ApprovalCard props：`{ turnId, items }`；动作四键（approve / reject+理由输入框 / 批准并本会话不再问 / 批准并永久允许·显示 suggestedRule）；提交组装 `items:[{callId,decision,reason}]` + remember → POST → 用返回的 SSE 流继续喂 `turn.ts` 合并进原 turn
- turn.ts：`ApprovalRequest` 归入独立分区 `approval`；`waiting_approval` turn 渲染挂起态；两段流按 turnId 合并（复用 callId 合并既有逻辑——新事件 append 到已有 turn）
- 409 处理：chat fetch 收 409 → 顶部横幅「有待审批操作，请先处理」+ 滚动到卡片 + `GET /approvals` 重渲染（刷新恢复同路径）
- PermissionSettings：表格（tool/pattern/effect/created_at + 删除按钮）+ 新增表单（tool 下拉 shell|write_file、pattern 输入）+ 内置白名单只读区

- [ ] **Step 1: vitest 先写 turn.ts 扩展测试**（参照现有 turn 测试文件风格）：

```typescript
// 用例 1：applyEvent(turn, {type:'approval_request', turnId, items}) → turn.approval 分区收到 items，
//         turn 状态置 waitingApproval（不置 done）
// 用例 2：两段流合并——同一 turnId 先 chat 段（thinking/text/toolCall 事件）后 resume 段
//         （toolResult/text/turnDone），断言合并后单 turn 完整且 callId 关联正确
// 用例 3：waitingApproval turn 再收 turnDone(COMPLETED) → 状态流转完成
```

→ 红 → 绿 → Commit
- [ ] **Step 2: ApprovalCard + 409 横幅 + 主组件接线**——卡片四动作键 + 底部「全部批准 / 全部拒绝」快捷键（提交组装为 items 全量同 decision）；拒绝展开理由输入框；提交 → POST → 返回 SSE 继续喂 `turn.ts` → `npx vitest run` 绿 → Commit
- [ ] **Step 3: PermissionSettings 页 + 路由 + api.ts** → 绿 → Commit `feat(approval): 前端审批卡片/409 横幅/设置页/两段流合并`

---

### Task 8: system prompt 措辞 + CLAUDE.md + 全量回归

**Files:**
- Modify: `agent/src/main/resources/prompts/system-prompt.md`（末尾追加一行，spec §9 原文：「工具调用可能触发人工审批：被拒绝时你会收到含理由的拒绝结果，请据此调整方案或征询用户意见。」）
- Modify: `CLAUDE.md`（架构节新增审批小节：规则引擎语义/事件/端点/双作用域/WAITING_APPROVAL 语义/spike 结论；已知边界补「审批规则非安全边界，文本可绕过」）
- Modify: spec 状态行 → 已实施

- [ ] **Step 1: 文档更新** → **Step 2: 全量回归** `mvn test && cd frontend && npx vitest run`（DB 测试带 `DB_USERNAME/DB_PASSWORD`）→ **Step 3: Commit** `docs: 审批机制 CLAUDE.md/system prompt 收尾 + 全量回归`
