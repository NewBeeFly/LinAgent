# javaAgent ReAct Agent 设计文档

- 日期：2026-09-20
- 状态：已与需求方逐节确认
- 路径：Architectural（全新子系统）
- 后续：本文档确认后，经 superpowers:writing-plans 产出实施计划

## 1. 背景与目标

基于 JDK 21 + Spring Boot 3.5.x + Spring AI Alibaba 1.1.2.3（Agent Framework，ReactAgent）从零搭建一个 ReAct Agent 服务：

- 模型：阶跃星辰 StepFun `step-3.7-flash`（OpenAI 兼容协议，本地密钥见 `openai_key.md`，实际配置走环境变量/本地配置文件，不入库）
- 前端 Vue3 聊天页，SSE 流式输出，**区分 thinking / message / tool 调用 / user message**
- 技能体系：项目本地 `skills/` 目录，区分**常驻技能**（全量注入 system prompt）与**渐进式技能**（`read_skill` 按需加载，对标 Claude Code 机制）
- 工具体系：预置 bash + 文件读写（常驻暴露）；技能专属工具通过 `groupedTools` 绑定技能，**read_skill 后才暴露**（渐进式披露）
- 数据库 PostgreSQL：多轮会话全量保留，按轮次结构化存储完整消息与工具调用，支持消息压缩
- 数据库连接：`localhost:5432`，账号 `jiege` / `***REMOVED***`，开发期新建独立库 `javaagent`

### 非目标（本期不做）

- Human-in-the-Loop 暂停/审批流
- 多 Agent 协作、子 Agent 编排
- 附件/多模态输入（模型支持 vision，本期仅文本）
- 用户体系/鉴权（单用户本地使用）

## 2. 技术选型（均经检索/实测验证，2026-09）

| 项 | 选型 | 版本 | 依据 |
|---|---|---|---|
| JDK | 21 | — | 需求指定 |
| Spring Boot | 3.5.x | — | SAA 1.1.2.3 兼容线 |
| Agent 框架 | spring-ai-alibaba-agent-framework | 1.1.2.3（最新稳定版，BOM 管理） | 2026-05 发布；2.x 仅为 2.0.0-M1.1 milestone，不采用 |
| 模型接入 | Spring AI OpenAI ChatModel → StepFun | Spring AI 1.1.2 | 实测 StepFun 端点 `supported_protocols=["chat","messages","responses"]`，chat completions 可用且返回 `reasoning_content` |
| 持久化 | PostgreSQL + Flyway + SAA JDBC checkpoint saver | PG 16+ | SAA 有现成 memory-jdbc starter |
| 接口 | Spring MVC + `Flux<ServerSentEvent>`（SSE） | — | 与 SAA `Flux<NodeOutput>` 流式天然衔接 |
| 前端 | Vue3 + Vite + TypeScript | — | 需求确认；fetch-stream 消费 POST SSE |

关键实测记录（已验证，非猜测）：

1. `GET /v1/models`：`step-3.7-flash`、`step-5-preview` 均支持 chat 协议 → 无需等 Spring AI 2.x / Responses API
2. `POST /v1/chat/completions`（非流式）：响应含 `reasoning` 与 `reasoning_content` 字段（DeepSeek 风格）→ thinking 可获取
3. Spring AI 1.1.x OpenAI 模块官方文档确认支持解析 OpenAI 兼容端点的 reasoning content；流式链路的取值方式需编码期实测（见 §8 风险）

## 3. 架构与模块结构

单仓库三模块（Maven 父工程 + 两个后端模块 + 前端独立目录）：

```
javaAgent/
├── pom.xml                      # 父 POM：dependencyManagement（SAA BOM + Spring AI BOM）
├── openai_key.md / openai_key_副本.md   # 密钥与 PG 信息备忘（敏感，git 忽略规则见 §9）
│
├── agent/                       # Maven 模块：Agent 引擎（库，不含启动类）
│   ├── pom.xml                  # agent-framework、spring-ai-openai、jdbc
│   └── src/main/java/com/javaagent/agent/
│       ├── config/              # ChatModelConfig(StepFun)、ReactAgentConfig
│       ├── facade/              # AgentFacade：chat(conversationId, content) → Flux<AgentEvent>
│       ├── skills/              # SkillBootstrap、FilteredSkillRegistry、frontmatter 解析
│       ├── tools/               # FileTools、ShellTool 装配、groupedTools 绑定
│       ├── persistence/         # turn/message 仓储 + PG checkpoint saver 接入
│       └── compaction/          # 消息压缩服务
│   └── src/main/resources/prompts/   # system-prompt 模板（文件化 + 启动缓存）
│
├── web/                         # Maven 模块：接口层（唯一可启动模块）
│   ├── pom.xml                  # 依赖 agent + spring-boot-starter-web
│   └── src/main/java/com/javaagent/web/
│       ├── WebApplication.java  # @SpringBootApplication（扫描含 agent 包）
│       ├── controller/          # ConversationController、ChatController(SSE)
│       ├── stream/              # SseEventMapper：AgentEvent → SSE 事件 + 落库触发
│       └── dto/                 # 前后端接口 DTO
│   └── src/main/resources/
│       ├── application.yml / application-local.yml
│       └── db/migration/        # Flyway 建表脚本
│
├── skills/                      # 本地技能目录（仓库根，SAA projectSkillsDirectory="./skills"）
│   ├── resident-*/SKILL.md      #   frontmatter 含 resident: true
│   └── progressive-*/SKILL.md   #   渐进式技能
│
└── frontend/                    # 前端独立目录（Vue3 + Vite + TS）
    ├── package.json
    ├── vite.config.ts           # dev 代理 /api → localhost:8080
    └── src/                     # 聊天页：会话列表 + thinking/message/tool 分区渲染
```

### 模块边界

| 模块 | 职责 | 依赖方向 |
|---|---|---|
| `agent` | ReAct 循环、技能、工具、checkpoint、消息持久化、压缩；对外仅暴露 `AgentFacade` + 领域事件 `AgentEvent` | 不依赖 web |
| `web` | REST/SSE 接口、DTO、SSE 事件映射、Flyway | → agent |
| `frontend` | Vue3 聊天界面 | 仅经 HTTP 与 web 交互 |

边界意图：`agent` 产出框架无关的领域事件流（thinking/text/tool_call/tool_result/turn_done/error），`web` 负责转 SSE——agent 不耦合传输协议，未来加 WebSocket/CLI 入口不动引擎层。

### ReactAgent 装配（agent 模块内）

```
ReactAgent (SAA)
 ├── model: OpenAiChatModel → StepFun（base-url/api-key/model 全配置化）
 ├── systemPrompt: prompts/ 身份模板 + 常驻技能全量内容（静态区）
 ├── hooks: SkillsAgentHook(FilteredSkillRegistry → 仅渐进式技能, autoReload=true)
 │          + ShellToolAgentHook(ShellTool2, 工作目录=项目根)
 ├── tools: FileTools（read_file/write_file/list_dir，路径安全校验）
 ├── groupedTools: 技能专属工具（read_skill 激活后暴露，会话内持续可用）
 ├── interceptors: ToolErrorInterceptor（工具失败回传错误文本，不断流）
 └── saver: PG checkpoint saver（threadId = conversationId）
```

## 4. 技能体系设计

### 目录与格式

`skills/<name>/SKILL.md`，frontmatter：

```yaml
---
name: skill-name          # 小写/数字/连字符，≤64 字符
description: 触发场景描述  # 用于模型自匹配
resident: true            # 可选；true=常驻技能
---
# 技能正文（渐进式技能建议 1.5k–2k tokens，长内容放 references/）
```

### 常驻 vs 渐进式分流

`SkillBootstrap` 启动时扫描 `skills/`，自行解析 frontmatter（SAA `SkillMetadata` 不含自定义字段，frontmatter 解析自实现，结果缓存）：

- `resident: true` → 全文拼入 system prompt 静态区（对齐 Claude Code "system prompt 静态区常驻内容"做法）
- 其余 → `FilteredSkillRegistry`（委托 `FileSystemSkillRegistry`，`listAll()/get()` 过滤 resident 技能），交给 `SkillsAgentHook` 渐进披露：系统提示仅注入技能列表（name/description/skillPath），模型按需调 `read_skill(skill_name)` 加载全文

### 工具的渐进式披露

- 常驻工具：ShellTool（bash）、FileTools，直接挂 `ReactAgent.tools()/hooks`，始终可用
- 渐进工具：`groupedTools: Map<skillName, List<ToolCallback>>` 绑定技能；模型对该技能调用 `read_skill` 后对应工具才加入当次及后续请求
- 技能目录放仓库根而非打入 JAR：开发期修改即时生效（`autoReload: true`）

## 5. 数据流与接口协议

### 一轮对话数据流

```
Vue3 → POST /api/conversations/{id}/chat {content}
     → ChatController: 创建 turn(RUNNING) + 落 USER message
     → AgentFacade.chat(convId, content) → ReactAgent.stream(input, threadId=convId)
     → Flux<NodeOutput>（OutputType 区分 LLM/工具/Hook 输出）
     → SseEventMapper: reasoning→thinking 事件、tool 调用→工具事件、文本→message 事件
     → SSE 逐事件推送前端；段落边界 flush 完整 message 落库
     → turn 置 COMPLETED/FAILED + usage 落库
```

### REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/conversations` | 创建会话 `{title?}` → `{id, title, createdAt}` |
| GET | `/api/conversations` | 会话列表（含摘要、轮次数） |
| GET | `/api/conversations/{id}/turns` | 历史回放：turn[] 每个嵌套完整 messages[] |
| DELETE | `/api/conversations/{id}` | 级联删除 turn/message/checkpoint |
| POST | `/api/conversations/{id}/chat` | SSE 流式对话，body `{content}` |

### SSE 事件协议

`event: <type>` + `data: <JSON>`；统一字段 `turnId`、`seq`（事件序号）：

| event | data 字段 | 说明 |
|---|---|---|
| `meta` | turnId, model, conversationId | turn 创建后首发 |
| `thinking_delta` | content | reasoning 增量（仅传输，不落库） |
| `message_delta` | content | 正文增量（仅传输，不落库） |
| `tool_call` | callId, toolName, arguments | 模型发起工具调用 |
| `tool_result` | callId, toolName, result, durationMs, success | 工具执行完成 |
| `turn_done` | finishReason, usage | 轮次正常结束 |
| `error` | code, message | 异常；发完即优雅关闭流 |

user message 不走 SSE 推送（前端本地渲染，后端落库）；它是回放接口里 message 类型之一。

## 6. 存储模型（PostgreSQL，Flyway）

```sql
conversation (id BIGSERIAL PK, title VARCHAR, created_at, updated_at)

turn (id BIGSERIAL PK, conversation_id FK, seq INT,          -- 轮次号
      status VARCHAR CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
      finish_reason VARCHAR, usage JSONB,
      started_at, finished_at)

message (id BIGSERIAL PK, turn_id FK, seq INT,               -- 轮内序号
         msg_type VARCHAR CHECK (msg_type IN
           ('USER','THINKING','TEXT','TOOL_CALL','TOOL_RESULT','ERROR')),
         content TEXT,        -- THINKING/TEXT/USER 完整文本
         call_id VARCHAR,     -- TOOL_CALL / TOOL_RESULT 关联键
         tool_name VARCHAR,
         arguments JSONB,     -- TOOL_CALL 完整入参
         result TEXT,         -- TOOL_RESULT 完整结果
         success BOOLEAN, duration_ms BIGINT,
         created_at)
-- index: (turn_id, seq)
```

设计要点：

- **delta 只是传输形态，不是存储形态**：SseEventMapper 内存累积增量，段落边界（工具调用开始/finish_reason/轮次结束）flush 一条完整 message
- 一轮 turn 的 messages 是有语义顺序的完整片段：USER → THINKING → TOOL_CALL → TOOL_RESULT → THINKING → TEXT …（工具后二次思考是新 THINKING 行）
- TOOL_CALL/TOOL_RESULT 各一行以 `call_id` 关联，arguments/result 结构化（JSONB），支持按工具聚合分析
- 查询聚焦：`GET .../turns` 一次返回整时间线；单轮、按工具统计均为简单 SQL
- checkpoint（SAA PG saver，threadId=conversationId）负责**给模型的记忆**；turn/message 负责**给前端的展示**，分工不冗余

### 消息压缩

- 阈值：`agent.compaction.threshold-tokens`（默认 24000），新轮开始前估算历史 token 超阈值即触发
- 处理：较早 turn（含 THINKING/TOOL 完整内容）交 LLM 摘要，替换 checkpoint 中旧消息
- 约束：`turn/message` 原始行不动，历史回放永远完整

## 7. 错误处理

| 层 | 处理 |
|---|---|
| 工具执行失败 | `ToolErrorInterceptor` 返回 `Tool failed: <msg>` 给模型继续推理；TOOL_RESULT(success=false) 落库 |
| 模型调用失败 | 流内 `error` 事件 + turn FAILED + finish_reason；SSE 优雅关闭不悬挂 |
| 路径安全 | FileTools/ShellTool canonical path 校验，必须在配置工作区根内，拒绝 `../` 逃逸 |
| 前端断连 | `Flux.doOnCancel` 中断执行，已完成片段照常落库，turn FAILED(CANCELLED) |
| 落库失败 | 事件流照常推送（展示优先），日志告警，turn 收尾补偿写 |

## 8. 已识别风险与预案

| 风险 | 预案 |
|---|---|
| Spring AI 1.1.2 流式链路中 `reasoning_content` 取值位置未实测确认 | 实施计划第一个任务即最小验证 demo（StepFun 流式 → reasoning → 事件）；兜底① `ModelInterceptor` 定制解析；兜底② 关闭 thinking；兜底③ 切 Responses 协议（StepFun 支持，切换隔离在 ChatModelConfig 一处） |
| SAA 框架 API 迭代快（issue #1316 类小坑） | 锁定 1.1.2.3 版本；集成测试覆盖流式路径 |
| SAA PG saver 具体类名/用法以编码期文档为准 | 实施时以 spring-ai-alibaba-starter-memory-jdbc 官方示例为准 |
| ShellTool 工作目录安全 | 统一路径校验器 + 单元测试反例覆盖 |

## 9. 安全与配置

- StepFun api-key、PG 密码走环境变量 / `application-local.yml`（`.gitignore` 排除，`openai_key*.md` 同样不入库）
- Flyway 管理表结构；开发期 `createdb javaagent` 建独立库
- 实施前置项：git init、Maven 父工程脚手架、frontend `npm create vite`（均写入实施计划首任务）

## 10. 测试策略

JUnit5 + Mockito + AssertJ + Testcontainers：

| 对象 | 方式 |
|---|---|
| frontmatter 解析 / FilteredSkillRegistry | 单元测试（临时目录构造 SKILL.md） |
| FileTools 路径安全 | 单元测试：正例 + `../` 逃逸反例 |
| 持久化（仓储、checkpoint saver、Flyway） | Testcontainers PG |
| SseEventMapper（delta 累积→完整消息、事件顺序） | 单元测试：构造 NodeOutput 序列断言 |
| ChatController SSE | MockMvc/WebTestClient + 打桩 AgentFacade，验证事件契约（meta→…→turn_done、error 路径） |
| 端到端 | @SpringBootTest + Testcontainers，打桩 ChatModel 返回含 tool_calls 的假响应，跑通全链路 |
| 前端 | Vitest：SSE 解析器（分片/粘包/半行）+ 事件→渲染分区映射 |

## 11. 验收标准

1. Vue3 聊天页发起对话，界面正确分区渲染 thinking（折叠块）/ message / tool 调用卡片
2. 一轮含工具调用的对话，PG 中 turn+message 完整还原该轮链路（含 thinking 全文、工具入参/结果）
3. 渐进式技能：系统提示只含技能列表，模型调用 `read_skill` 后能按技能内容执行；常驻技能内容出现在 system prompt
4. groupedTools 绑定的工具在 read_skill 前不可见、激活后可用
5. 重启应用后按 conversationId 恢复会话继续对话（checkpoint 生效）
6. 历史超阈值触发压缩，回放仍完整
7. 工具执行失败不中断流；前端断连 turn 落 FAILED 可恢复
