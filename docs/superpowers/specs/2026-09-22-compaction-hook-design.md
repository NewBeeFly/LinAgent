# LinAgent 压缩策略重构设计文档（hook 化全量摘要）

- 日期：2026-09-22
- 状态：已实施（feat/compaction-hook，实施计划 docs/superpowers/plans/2026-09-22-compaction-hook.md）
- 路径：Architectural（重设计压缩子系统，替换现有 CompactionService 换代体系）
- 后续：本文档确认后，经 superpowers:writing-plans 产出实施计划，分支 `feat/compaction-hook` 迭代

## 1. 背景与目标

现有压缩（`CompactionService`）存在一组已实证的结构性缺陷，且不满足长任务（跨多轮长会话 / 单轮深工具循环 / 跨会话任务接力）的演进需求：

1. **摘要 SystemMessage 每轮重复叠加**（bug 级）：压缩后每轮以 `stream(List.of(SystemMessage(摘要), UserMessage))` 进图，messages 键为 `AppendStrategy`（ReactAgent.java:746），摘要被逐轮 append 进 checkpoint state——第 N 轮模型输入含 N 份相同摘要快照；SAA `AgentLlmNode.appendSystemPromptIfNeeded` 不去重并警告多条 SystemMessage 致模型困惑。
2. **估算口径失真**：估算基于展示库（message 表）content+result 字符 `/4`——中文低估 2-3×，且不含 system prompt / 工具调用结构 / arguments，触发偏晚。
3. **压缩调用同步阻塞 SSE 热路径且零容错**：摘要 LLM 失败 = 当轮对话整体失败。
4. **换代体系复杂**：threadId `conv-{id}-v{n}` 换代、锚点列、摘要列、CheckpointCleaner `-v%` 清理互相耦合；压缩后近期上下文全丢（无保留区）。

### 目标

- 压缩迁移到 SAA hook 机制（`MessagesModelHook` + `HookPosition.BEFORE_MODEL` + `UpdatePolicy.REPLACE`），在**真实内存消息列表**上计数、切割、摘要，同 threadId 原地替换，不换代。
- `AgentFacade` 纯化为「只传当前 UserMessage，历史由 checkpoint 恢复合并」。
- 根治摘要重复注入（REPLACE 语义下摘要仅存一份于列表头部，触发时被合并再摘）。
- 摘要调用缓存友好（cache-safe：原消息作前缀 + 压缩指令尾部追加），失败保持原列表不丢轮次。
- 为长任务后续迭代留接缝：工具结果截断层、跨会话任务接力（`CompactionSummarySink`）。

### 非目标（本期不做）

- 工具结果截断/清理层（下一迭代，落点在工具结果进 state 前，与本 hook 无耦合）
- 跨会话任务接力的实现（仅接口预留，MVP no-op）
- 摘要落库展示（前端已核实不消费 `compact_summary`，新体系摘要只进 checkpoint）
- metrics 基建（可观测全走日志）
- 存量 `-v` 换代线程的数据迁移（CheckpointCleaner 保留前缀清理能力，存量自然清理）

## 2. 关键实证依据（2026-09-22 本会话源码核实，非猜测）

| # | 结论 | 依据 |
|---|---|---|
| 1 | SAA 1.1.2.3 内置 `SummarizationHook`，但其 `createSummary` 失败时把错误字符串塞进 system message 且为 private 无法 override | sources jar `hook/summarization/SummarizationHook.java:263-285` |
| 2 | messages 键为 `AppendStrategy`，stream 入参整体追加进 state | ReactAgent.java:746 + Agent.java:545 `buildMessageInput` |
| 3 | system prompt 由 `AgentLlmNode` 放 `request.systemMessage`（messages[0] 插入），不在 state messages 内——hook 只见对话历史，system prompt 天然不受压缩影响 | AgentLlmNode.java:205-207, 313-336 |
| 4 | **thinking 不回传**：请求构造只调 `getText()/getToolCalls()/getMedia()`，metadata（含 reasoningContent）零读取——StepFun OpenAI 兼容栈无「必须回传思考」约束；压缩无需处理 thinking（不在模型上下文，仅占 checkpoint 存储体积） | 反汇编 spring-ai-openai 1.1.2 `OpenAiChatModel.createRequest` |
| 5 | StepFun 自动前缀缓存：256 token 块、逐 token 精确匹配、LRU 无固定 TTL、`usage.cached_tokens` 可验证 | platform.stepfun.com/docs/zh/guides/developer/prompt-cache 实抓 |
| 6 | 官方文档将自定义 `MessagesModelHook` 作为一等扩展点教学；`AgentCommand` + `UpdatePolicy.REPLACE` 语义在 SkillsInterceptor 同机制验证 | java2ai.com agent-framework tutorials（hooks / memory / context-engineering） |

## 3. 方案选择记录

- **A. 官方 SummarizationHook 直接挂**：被否。失败降级字符串进 system 且无法 override；摘要请求全价计费；不可观测。「官方 hook + 外围补丁」不可行（private 方法）。
- **B. 自定义 `MessagesModelHook`（选定）**：骨架抄官方 SummarizationHook，替换失败处理、切割边界、摘要调用方式、埋点。约 200 行 + 测试。

## 4. 架构

```
AgentFacade.chat()
  └─ stream(new UserMessage(content), config)        ← 注入分支删除；只传当前消息
       └─ PostgresSaver 按 threadId 恢复 state.messages（AppendStrategy 合并当前输入）
            └─ 每次模型调用前 SummarizingModelHook.beforeModel()   ← 新增
                 ├─ 估算 token（chars/2 口径）≤ 阈值 → 原样放行
                 ├─ 超阈值 → 切割：保最近 K=20 个完整 turn + 首条 UserMessage，
                 │    工具配对（AssistantMessage(toolCalls)/ToolResponseMessage）不拆
                 ├─ 摘要（cache-safe）：[...切割点前原消息（原样前缀）, UserMessage(压缩指令)]，
                 │    同一 ChatModel；try-catch + 超时 → 失败返回原列表本轮跳过
                 ├─ 成功 → AgentCommand([SystemMessage(摘要), 首user, ...近K轮], UpdatePolicy.REPLACE)
                 └─ CompactionSummarySink.onSummary(summary, ctx)   ← 接力预留，MVP no-op
```

组件与职责：

| 组件 | 职责 | 变更 |
|---|---|---|
| `SummarizingModelHook`（新，`agent/compaction/`） | 计数、切割、摘要、REPLACE、埋点；无状态单例 Bean | 新增 |
| `AgentFactory` | hooks 列表加挂 hook | 改 |
| `AgentFacade` | 删摘要注入分支（117-123），统一 UserMessage 单参重载 | 改 |
| `CompactionService` | — | 删除 |
| `CompactionSummarySink`（新接口 + no-op 实现） | 摘要产出回调，跨会话接力预留 | 新增 |
| `CheckpointCleaner` | 存量 `-v%` 清理保留 | 不变 |
| `Conversation` PO | `compactSummary`/`compactedTurnSeq` 字段保留（不动 DDL），代码停止读写 | 改 |

## 5. 核心机制

### 5.1 触发与计数

- 时机：每次模型调用前（含轮内工具循环每步）——「接近极限提前释放」的动态检查；不压缩时开销为一次线性求和。
- 口径：`chars/2`（中文校准）；系数配置化 `agent.compaction.chars-per-token`（默认 2）。
- 阈值：`agent.compaction.threshold-tokens`（默认初值 48000，/2 口径；集成期按 system prompt 实际体量与 `usage.prompt_tokens` 对照日志定稿）。
- 守卫：可切割内容为空（不足 K 个完整 turn，切割点 ≤ 0）时不压缩，防「触发即空转」。

### 5.2 切割与保留

- 保留区 = 最近 **K=20 个完整 turn**（`agent.compaction.keep-turns`，默认 20）：turn 边界 = UserMessage 开新轮；切割点落在第 K 个 UserMessage 之前。
- 工具配对保护：切割点回退搜索，不拆散 AssistantMessage(toolCalls)/ToolResponseMessage 配对（复用官方 `findSafeCutoff`/`isSafeCutoffPoint` 逻辑，本地源码可参照）。
- 首条 UserMessage 永久保留（不进摘要）。
- 链式合并：头部旧摘要 SystemMessage 随切割点前消息一起被再摘要——等价旧「既有摘要作合并上下文」语义，且天然不叠加（根治点）。

### 5.3 摘要生成（cache-safe + 容错）

- 请求体：`[...切割点前原消息（字节原样，作缓存前缀）, UserMessage(压缩指令)]`；同一 ChatModel（跨模型缓存不共享）。
- 压缩指令：由现 `SUMMARY_SYSTEM_PROMPT` 演进为尾部 user message（不碰前缀），要求保留用户目标 / 已完成的关键操作与结论 / 未决事项，直接输出摘要正文。
- 失败处理：try-catch + 超时（`agent.compaction.summary-timeout`，默认 60s）→ 返回 `AgentCommand(previousMessages)`（原列表），本轮跳过、下轮再试；失败即告警日志（不去重、不熔断，对齐「宁可超长不丢记忆」）。错误信息永不进入上下文。
- `CompactionSummarySink.onSummary(String summary, SummaryContext ctx)`：MVP no-op（日志）；接力迭代在此实现持久化/下会话注入，不动 hook 本体。

### 5.4 数据影响边界（需求方确认）

- 压缩**只作用于 graph state（graphcheckpoint 表）**；展示层 turn/message 表一行不动，前端回放永远看全量历史（含被摘要轮次）。
- 「前端可见历史 ≠ 模型实际上下文」为有意设计（延续既有解耦原则）。
- threadId 恒为 `conv-{id}`，不再换代。

## 6. 可观测（日志，不引 metrics）

- 触发时 `log.info`：threadId / 估算 tokens / 阈值 / 切割点 turn 位置 / 摘要前后消息数与估算 token / 摘要耗时。
- 摘要调用自身 usage（含 `cached_tokens` 若返回）——验证 cache-safe 命中。
- 对照日志：ThinkingTap 捕获的主调用 `usage.prompt_tokens` vs hook 估算 → 系数校准依据。

## 7. 测试策略

单测（hook 纯逻辑，无容器）：
1. 低于阈值原样放行
2. 超阈值：保 K turn 保留区 + 首条 user + 工具配对不拆（构造 toolCalls/ToolResponse 序列）
3. 不足 K turn 不压缩
4. 摘要失败/超时返回原列表（错误串不进上下文）
5. 头部旧摘要被合并再摘、不叠加
6. token 计数口径（chars-per-token 生效）

集成（Testcontainers PG + 桩 ChatModel）：
1. 多轮灌超阈值 → checkpoint 消息被 REPLACE、条数下降、摘要 SystemMessage 恰 1 份
2. **根治终验**：压缩后连续 3 轮，每轮模型输入中摘要 SystemMessage 计数 == 1
3. 展示层零变化断言：压缩前后 turn/message 表逐行一致
4. 现有 AgentFacade 测试更新（删摘要注入断言），全量 `mvn test` 通过

## 8. 配置清单

```
agent.compaction.threshold-tokens   # 默认 48000（/2 口径初值，集成期定稿）
agent.compaction.keep-turns         # 默认 20
agent.compaction.summary-timeout    # 默认 60s
agent.compaction.chars-per-token    # 默认 2（预留校准）
```

## 9. 实施顺序（供 writing-plans 展开）

1. 新建分支 `feat/compaction-hook`
2. `SummarizingModelHook` + `CompactionSummarySink`（TDD：单测先行）
3. `AgentFactory` 挂载 + `AgentFacade` 纯化 + `CompactionService` 删除 + PO 停读写
4. 集成测试（含根治终验）+ 全量回归
5. 可观测日志核对（cached_tokens / prompt_tokens 对照）
