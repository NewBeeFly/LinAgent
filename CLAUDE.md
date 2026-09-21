# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 常用命令

```bash
# 后端全量测试（pom 已默认排除 manual 分组，不会误触真实 API）
mvn test

# 单个测试
mvn -pl agent test -Dtest=SegmentBufferTest
mvn -pl web test -Dtest=ChatControllerSseTest

# 真实 API 测试（manual 分组，消耗 StepFun 配额，显式触发才跑）
mvn -pl agent test -Dtest=StepFunStreamingProbeTest -Dgroups=manual -Dsurefire.excludedGroups=
mvn -pl web test -Dtest=SkillsSmokeTest -Dgroups=manual -Dsurefire.excludedGroups=

# 双身份手工验证（curl 示例）
curl -H "x-tenant-id: default" -H "x-user-id: linmj" http://localhost:8080/api/conversations
# 前端身份经 VITE_TENANT_ID/VITE_USER_ID 配置（缺省 default/linmj）

# 前端
cd frontend && npx vitest run          # 单测
cd frontend && npm run dev             # dev server（代理 /api → :8080）

# 启动后端（agent 模块改动后必须先 install，否则 classpath 用的是旧 jar）
mvn -pl agent install -DskipTests && mvn -pl web spring-boot:run
```

注意 `mvn -pl web -am spring-boot:run` 会把 run goal 跑到父 POM 上报 "Unable to find a suitable main class"——不要用 `-am`。

## 架构

Maven 三模块 + 前端独立目录：

- `agent/`：引擎库（不依赖 web）。对外唯一门面 `AgentFacade.chat(conversationId, content) → Flux<AgentEvent>`（sealed 接口，七类事件）。`web/` 只做 `AgentEvent → SSE` 协议转换（SseEventMapper），零持久化职责。
- `web/`：唯一可启动模块（`WebApplication`，scanBasePackages + `@EnableJdbcRepositories` 指向 agent 的 persistence 包）。
- `frontend/`：Vue3 + Vite + TS。`turn.ts` 是实时 SSE 与历史回放共用的纯逻辑层（事件分区/工具按 callId 合并）。
- `skills/`：仓库根目录（不在 jar 里）。frontmatter `resident: true` 的常驻技能全文注入 system prompt 静态区；其余经 `FilteredSkillRegistry` 交给 SAA `SkillsAgentHook` 渐进披露（模型调 `read_skill` 按需加载）。frontmatter 由自研 `SkillManifestScanner` 解析（SAA 的 SkillMetadata 不含自定义字段）。

### 身份与工作区（v0.2）

请求带 `x-tenant-id`/`x-user-id`（app_user 表校验，未知 401）。AuthContextFilter →
AuthContextHolder（ThreadLocal，仅入口有效）→ facade.chat() **defer 外**捕获（订阅期在
reactor 线程，届时已清理——defer 内读取是已实证的坑）。工具每轮构造，个人根 =
`{agent.workspace-root}/{tenant}/users/{user}`，根内 `shared` 符号链接挂载租户共享区
（WorkspaceResolver 幂等 provision）。conversation 按 (tenant_id, user_id) 隔离，非属主 404。
前端身份走 VITE_TENANT_ID/VITE_USER_ID（缺省 default/linmj）。

### 事件流核心（AgentFacade，改这里先读懂）

三路合并：① thinking 增量——`ThinkingTapChatModel`（ChatModel 装饰器）在 stream 的 doOnNext 里把 reasoning 旁路发进 `SerializedEmitSink`；② 工具事件——`EventEmittingToolInterceptor`（SAA ToolInterceptor）直发同一 sink，异常降级为 `Tool failed: ...` 回传模型不断流；③ 正文 delta——`agent.stream()` 的 NodeOutput 流。`TurnDone` 在 main 流 concatWith 收尾；CANCEL 路径经 once-guard（AtomicBoolean）+ status==RUNNING 复核防终态双写。

并发约束（有 8×200 并发用例守护）：**所有事件发射必须经 `SerializedEmitSink`**（unicast sink 非线程安全）；**SegmentBuffer 全方法 synchronized + AtomicInteger seq**（recordToolCall 的 flush+save+seq 分配必须在同一临界区）。锁序单向：sink → buffer，无反向嵌套。

每轮对话构建新 ReactAgent 实例（AgentFactory），saver/技能注册表/工具为共享 Bean。

### 持久化分工（容易搞混）

- **给模型的记忆**：SAA `PostgresSaver`（graph-core 内置，表 graphthread/graphcheckpoint），threadId = conversation.threadId。**threadId 命名是硬契约**：`conv-{id}` 原始线程、`conv-{id}-v{n}` 压缩换代线程——`CheckpointCleaner` 的删除模式依赖它。
- **给前端的展示**：turn/message 表，**不存 delta**——THINKING/TEXT 按段落边界（工具调用开始/轮次结束）flush 完整 Message（SegmentBuffer 累积）；TOOL_CALL/TOOL_RESULT 各一行以 call_id 关联。
- 压缩：`CompactionService` 阈值（`agent.compaction.threshold-tokens`，字符/4 估算）触发 LLM 摘要，写 `conversation.compact_summary` + threadId 换代 + 锚点列 `compacted_turn_seq`（估算基准 = 摘要/4 + 锚点后增量，防每轮重复压缩）。展示存储永不改动。

## 实证过的坑（SAA 1.1.2.3 / Spring AI 1.1.2，勿凭记忆推翻）

- 依赖栈是**精简版 spring-ai-model**：`ToolCallbacks.from()` 不存在 → 用 `MethodToolCallbackProvider.builder().toolObjects(obj).build().getToolCallbacks()`。
- `AssistantMessage` 无双参 public 构造器 → 用 builder。
- StepFun `reasoning_content` 落在 `result.output.metadata["reasoningContent"]`（ThinkingExtractor 首选键）；流末尾有**无 choices 的 usage 尾包**（getResult()==null，消费必须判空）；thinking 尾与 text 首可能同 chunk。
- `agent.stream(UserMessage, RunnableConfig)` 抛**受检** GraphRunnerException；`StreamingOutput.chunk()` 已弃用，从 Message 提取。
- SAA `SkillRegistry` 签名：`get` 返回 `Optional<SkillMetadata>`、`readSkillContent` 返回 String 缺失抛 ISE、`SkillMetadata.getName()`；实现 FilteredSkillRegistry 时 `getByPath/disable/isDisabled` 必须 override（default 实现会漏过滤）。
- `PostgresSaver` 不在 starter-memory-jdbc 里（那个只有已弃用的 ChatMemoryRepository 体系），在 graph-core。V2__checkpoint.sql 的 DDL 必须与 saver 内置 DDL 逐字符一致，saver 以 CREATE_NONE 模式初始化（DDL 归 Flyway 管）。
- JSONB 直写需要 JDBC URL 带 `?stringtype=unspecified`（已固化在 application.yml 的 url 模板；`@ServiceConnection` 测试容器要用 `withUrlParam` 补）。
- system prompt 模板（`agent/src/main/resources/prompts/system-prompt.md`）**启动时缓存**（ResidentPromptBuilder），改完必须重启后端才生效。
- `mvn -pl web spring-boot:run` fork 出的 JVM **工作目录是 web 模块目录**：`agent.skills-root`/`agent.workspace-root` 这类相对路径配置裸解析会落到 `web/skills`（不存在）导致 0 技能加载（0 技能时模型会幻觉编造技能名）。必须经 `ProjectPathResolver.resolveDir`（cwd → 父目录上溯一级）解析，直接 `Path.of(相对路径)` 是回归。
- @WebMvcTest 切片会自动装配 Filter 类型 Bean：AuthContextFilter 落地后所有 @WebMvcTest 必须 @MockBean RequestAuthenticator 并打桩（返回 TestAuth.LINMJ_CTX），否则 401/上下文失败。

## 前端两个易踩点

- `send()` 的 turn 必须 `reactive(newTurn(text))` 包裹——raw 对象的增量修改不触发 Vue 响应式，会导致整轮内容等流结束一次性出现（修过一次，有回归测试）。
- 思考折叠块展开态 = `thinkingActive(turn)`（流式中且正文为空），ThinkingBlock 内 watch streaming 驱动自动开合，勿直接改 `open` 初值。

## 历史文档

`docs/superpowers/specs|plans/` 是项目初建时的设计与实施存档（当时项目名 javaAgent），记录了全部技术决策的来龙去脉；`docs/` 不随更名改动。
