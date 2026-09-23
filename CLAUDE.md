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
curl -H "x-tenant-id: default" -H "x-user-id: linmj" http://localhost:9018/api/conversations
# 前端身份经 VITE_TENANT_ID/VITE_USER_ID 配置（缺省 default/linmj）

# 前端
cd frontend && npx vitest run          # 单测
cd frontend && npm run dev             # dev server（代理 /api → :9018）

# 启动后端（agent 模块改动后必须先 install，否则 classpath 用的是旧 jar）
mvn -pl agent install -DskipTests && mvn -pl web spring-boot:run
```

注意 `mvn -pl web -am spring-boot:run` 会把 run goal 跑到父 POM 上报 "Unable to find a suitable main class"——不要用 `-am`。

## 架构

Maven 三模块 + 前端独立目录：

- `agent/`：引擎库（不依赖 web）。对外唯一门面 `AgentFacade.chat(conversationId, content)` / `resume(conversationId, feedback)` → `Flux<AgentEvent>`（sealed 接口，八类事件）。`web/` 只做 `AgentEvent → SSE` 协议转换（SseEventMapper），零持久化职责。
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
- 已知边界：shell 工具仅以个人根为 cwd，无 OS 级沙箱——跨用户/跨租户文件可经 shell 命令访问；文件级隔离由 read_file/write_file/list_dir 的越界校验承担。多用户生产化前须补 shell 策略（deny-by-default / 按用户禁用）。审批规则（见「工具审批 HITL」）同理**非安全边界**——匹配基于命令文本，`bash -c`、`/bin/rm` 等可绕过前缀规则；不依赖命令文本的强制隔离须 OS 级沙箱（spec 非目标）。

### 事件流核心（AgentFacade，改这里先读懂）

三路合并：① thinking 增量——`ThinkingTapChatModel`（ChatModel 装饰器）在 stream 的 doOnNext 里把 reasoning 旁路发进 `SerializedEmitSink`；② 工具事件——`EventEmittingToolInterceptor`（SAA ToolInterceptor）直发同一 sink，异常降级为 `Tool failed: ...` 回传模型不断流；③ 正文 delta——`agent.stream()` 的 NodeOutput 流。`TurnDone` 在 main 流 concatWith 收尾；CANCEL 路径经 once-guard（AtomicBoolean）+ status==RUNNING 复核防终态双写。

并发约束（有 8×200 并发用例守护）：**所有事件发射必须经 `SerializedEmitSink`**（unicast sink 非线程安全）；**SegmentBuffer 全方法 synchronized + AtomicInteger seq**（recordToolCall 的 flush+save+seq 分配必须在同一临界区）。锁序单向：sink → buffer，无反向嵌套。

每轮对话构建新 ReactAgent 实例（AgentFactory），saver/技能注册表为共享 Bean；工具每轮构造（v0.2 个人根烤入，见「身份与工作区」）；审批 hook 与规则引擎同样随轮构造（见「工具审批 HITL」）。

### 持久化分工（容易搞混）

- **给模型的记忆**：SAA `PostgresSaver`（graph-core 内置，表 graphthread/graphcheckpoint），threadId = conversation.threadId，恒为 `conv-{id}`（不再压缩换代）；`CheckpointCleaner` 按 `conv-{id}` 前缀删除（`-v%` 模式仅为兼容历史行保留）。
- **给前端的展示**：turn/message 表，**不存 delta**——THINKING/TEXT 按段落边界（工具调用开始/轮次结束）flush 完整 Message（SegmentBuffer 累积）；TOOL_CALL/TOOL_RESULT 各一行以 call_id 关联。
- 压缩：`SummarizingModelHook`（BEFORE_MODEL，AgentFactory 挂载）对**真实 state messages**
  估算 token（`agent.compaction.chars-per-token`，默认 2），超生效阈值时保最近
  `keep-turns`（默认 20）个完整 turn + 首条 UserMessage，其余经 cache-safe 调用（原消息前缀
  + 尾部压缩指令，`reasoning_effort=low` 限思考）生成摘要，`UpdatePolicy.REPLACE` 原地替换——
  **不换 threadId、不碰展示存储**；失败/超时原样放行。**阈值派生**：`threshold-tokens` 显式
  配置（>0）优先，否则 = `max-context-tokens`（262144，step-3.7-flash 256K）× `trigger-ratio`
  （默认 0.8）；**幂等守卫**：被摘区仅剩旧摘要/首条用户消息（无原文）时跳过——防深工具轮
  「每步触发、收缩恒零」的重复摘要循环（实测曾致 30s 级无效摘要调用）。
  新会话 threadId 恒为 `conv-{id}`（存量 `-v{n}` 线程原样沿用，永不回写）；
  `compact_summary`/`compacted_turn_seq` 列停用（PO 字段保留，创建时摘要列传 null、锚点列传 0
  （NOT NULL 列））；`CompactionSummarySink` 为跨会话接力预留（MVP LoggingSummarySink）。
  已实证坑：`AgentCommand.getMessages()` 包私有——hook 核心逻辑须收在包可见 `compact()` 供单测。

### 工具审批 HITL（approval 包）

- 判定引擎 `PermissionRuleEngine`（纯逻辑无 IO，每轮随 userRules 查库新建）：评估序 **BUILTIN 白名单（shell 只读 20 命令）→ session（`InMemorySessionRules`，key=tenant:user:conv:tool）→ user（permission_rule 表 V6，UNIQUE 四键冲突安全落库）→ 待审批**，命中即放行。`*` 段通配（`pip install *` 命中 `pip install pandas`、不命中 `pip installx`）；复合命令按 `&&/||/;/|` 拆段**全命中才放行**；段含 `>`/`<`/`$(`/反引号/`find -delete|-exec` 即丧失 BUILTIN 资格（fail-safe 落审批）；read_file/list_dir/csv_summary/read_skill 恒放行（越界校验归工具层）。
- `ApprovalHook`（抄 SAA `HumanInTheLoopHook` 骨架、判定层换引擎；每轮随 AgentFactory 构造，非 Bean）：工具节点前 `interrupt()` 判定 → 中断（checkpoint 持久化）→ 流尾 `AgentEvent.ApprovalRequest`（**不发 TurnDone**）+ 待审批 TOOL_CALL 提前落 message 表 + turn.status=WAITING_APPROVAL（V7 放宽 turn_status_check）。
- resume：`POST /api/conversations/{id}/approvals` 整批 per-callId 决策 + remember once|session|forever（forever 服务端按 `suggestPattern` 重算落库）→ `agent.stream(Map.of(), config.addMetadata(HUMAN_FEEDBACK_METADATA_KEY, InterruptionMetadata))` 续流 SSE（续跑再中断则再发 ApprovalRequest，递归语义）；`GET .../approvals` 刷新恢复；chat 遇 WAITING_APPROVAL → 409（先决议后继续）；`/api/permission-rules` CRUD 三端点；前端 ApprovalCard / 409 横幅 / 设置页（哈希路由 `#/settings/permissions`）。
- 拒绝语义：REJECTED+理由作为 tool result 回传模型（SAA 内置文案），模型据此调整方案自然收尾；审批对模型透明（从不询问模型）。

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
- `ReactAgent.initGraph` 只对**具体类型** `HumanInTheLoopHook`/`InterruptionHook` 把 hook 实例注册为节点 action（实现 InterruptableAction，apply 前会调 `interrupt()`）；其余自定义 ModelHook 一律被 lambda 包装（仅 afterModel）→ `interrupt()` **永不触发**——审批静默失效、工具直执行，无任何报错。修复：`AgentFactory.registerInterruptibleApprovalNode`——图编译后反射换 `CompiledGraph.nodeFactories` 中该节点的 action 为 hook 本体（节点名按 `getFullHookName` 前缀定位、不硬编码位置后缀；找不到节点/反射失败一律响亮抛 ISE——静默降级=审批整体失效）；`ApprovalEndToEndTest` E2E 守护。`HumanInTheLoopHook` 构造器 private 无法继承。**SAA 升级必须回归此点**。
- checkpoint 续跑（resume 的 stream）不经过 BEFORE_AGENT 节点 → `ShellToolAgentHook` 的 shell 会话未初始化，续跑首个 shell 调用报 "Shell session not initialized"。修复：`AgentHandle.resumePrimer`（resume 开流前 `shellTool.getSessionManager().initialize(config)` 幂等补齐；chat 路径 no-op）。
- resume 的 `agent.stream()` 第一参必须 `Map.of()`——传 UserMessage 会产生乱序幻影消息污染历史（spike 结论 B，SaaInterruptionSpikeTest 实证）。

## 代码结构与风格约束（新增代码必读，评审对照）

### 数据访问（SQL 只许两处存在：@Query 注解与 Flyway 文件）
- 优先级：**派生查询**（findByXxx）→ **@Query 注解**（派生写不出/启动期解析坑时）→ 禁止 JdbcTemplate 裸写字符串 SQL；DDL 一律只在 Flyway 迁移文件
- PO 一律 record + `@Table`，放 `persistence/po/`；仓库接口放 `persistence/repository/`；JdbcConverterConfig/CheckpointCleaner 等运维件放 `persistence/support/`
- 只读无 @Id 的复合键表（app_user）或外部表投影（graphthread）：用**查询型仓库接口**（`extends Repository<PO, String>` + @Query），不继承 CrudRepository；仓库接口必须挂一个 PersistentEntity 载体 PO（`Repository<Void,…>` 会启动期崩）
- 实证坑：SD JDBC 3.5.1 派生查询在实体无对应字段时**启动期** QueryCreationException（不是运行期）；PG 对同一命名参数在 CONCAT 里二次出现报 "could not determine data type"（拆双参数解决）
- @WebMvcTest 切片会实例化 `@EnableJdbcRepositories` 注册的**所有**仓库接口：新增仓库接口后，各切片测试必须补对应 @MockBean

### 命名
- Java 方法/变量一律驼峰；模型侧工具名保持 snake_case 稳定契约——用 `@Tool(name = "read_file")` 别名 + 驼峰方法名（`readFile`），**不要**给 Java 方法起下划线名
- 工具名（read_file/write_file/list_dir/csv_summary/read_skill）被 system prompt、技能文档、SkillsSmokeTest 断言引用，改名=破坏外部契约，只能靠别名承接
- 列名蛇形 ↔ 字段驼峰由 SD JDBC 默认命名策略自动映射（Conversation.compactSummary ↔ compact_summary 已实证）

### 判空政策
- **边界判空必要**：外部输入（header/请求体）、协议可空尾包（StepFun usage 空包）、JSONB/可空列 metadata、`@RequestBody(required=false)`——这些是实证过的坑，不许"优化"掉
- **内部不变量不判空**：构造函数刚赋值的字段、record 自带不可变约定——多余判空视为噪音
- 工具/中间层重复防御（同一数据上游已校验）不叠加

## 前端两个易踩点

- `send()` 的 turn 必须 `reactive(newTurn(text))` 包裹——raw 对象的增量修改不触发 Vue 响应式，会导致整轮内容等流结束一次性出现（修过一次，有回归测试）。
- 思考折叠块展开态 = `thinkingActive(turn)`（流式中且正文为空），ThinkingBlock 内 watch streaming 驱动自动开合，勿直接改 `open` 初值。

## 历史文档

`docs/superpowers/specs|plans/` 是项目初建时的设计与实施存档（当时项目名 javaAgent），记录了全部技术决策的来龙去脉；`docs/` 不随更名改动。
