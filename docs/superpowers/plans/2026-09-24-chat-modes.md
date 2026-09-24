# 会话模式（Chat Modes）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三档会话模式（AUTO 自由/STANDARD 标准/CHAT 纯聊）——conversation 列存储、AgentFactory 构造分支、PUT 端点、前端切换器。

**Architecture:** V8 加 `conversation.mode` 列（默认 STANDARD）；facade 读 mode 传 AgentFactory（AUTO 不挂 ApprovalHook；CHAT tools 空 + 仅压缩 hook + prompt 追加声明）；`PUT /api/conversations/{id}/mode` 切换（pending 轮 409）；前端头部三段切换器。

**Tech Stack:** SD JDBC + Flyway V8、SAA 1.1.2.3（空 tools 已核实合法：ReactAgent `hasTools` 标志位）、JUnit5/Testcontainers、Vue3。

**Spec:** `docs/superpowers/specs/2026-09-24-chat-modes-design.md`

## Global Constraints

- 分支：`feat/chat-modes`（Task 1 创建）
- mode 值域 `AUTO|STANDARD|CHAT`（DB 存大写码）；新会话默认 STANDARD；切换下一轮生效
- **Conversation record 现为 9 字段**（id,title,threadId,compactSummary,compactedTurnSeq,tenantId,userId,createdAt,updatedAt）——mode 追加为第 10 位（updatedAt 前）；**全部 `new Conversation(...)` 调用点必须补参**（已排查：ConversationController 两段式落库、CheckpointCleanerTest、ApprovalEndToEndTest、AgentFacadeTest 多处 InMemory double）
- CHAT 模式 prompt 追加句（spec §2.3 原文）：「当前为纯对话模式，无任何工具可用，请直接回答，不要声称会执行操作。」——拼接点在 AgentFactory（ResidentPromptBuilder 缓存串之后），**不改 ResidentPromptBuilder 本身**
- PUT mode：非法枚举 400 / 归属校验 / WAITING_APPROVAL 存在 409
- Java 驼峰；PO record + @Table；SQL 只许 @Query 与 Flyway；@WebMvcTest 切片新依赖补 @MockBean
- 每任务收尾：测试绿 + `mvn -pl agent install -DskipTests`（agent 改动后）

---

### Task 1: 分支 + V8 迁移 + ChatMode + Conversation.mode

**Files:**
- Create: `agent/src/main/resources/db/migration/V8__chat_mode.sql`
- Create: `agent/src/main/java/com/linagent/agent/conversation/ChatMode.java`（枚举 + `parse` 容错）
- Modify: `agent/src/main/java/com/linagent/agent/persistence/po/Conversation.java`
- Modify: 全部 `new Conversation(` 调用点（ConversationController / CheckpointCleanerTest / ApprovalEndToEndTest / AgentFacadeTest——rg 定位）
- Test: `agent/src/test/java/com/linagent/agent/conversation/ChatModeTest.java` + 既有测试修复

**Interfaces:**
- Produces: `enum ChatMode { AUTO, STANDARD, CHAT }` + `static ChatMode parse(String s)`（null/未知 → STANDARD，不抛）；`Conversation(..., Instant createdAt, Instant updatedAt)` 尾部前插 `String mode`；工厂 `Conversation.create(...)` 默认 STANDARD

- [ ] **Step 1** 建分支 `git checkout -b feat/chat-modes`
- [ ] **Step 2** V8 DDL：`ALTER TABLE conversation ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD';`（注释：会话模式 AUTO|STANDARD|CHAT）
- [ ] **Step 3** ChatMode 枚举 + ChatModeTest（parse 三档/null/垃圾值→STANDARD）——RED→GREEN
- [ ] **Step 4** Conversation 加字段 + create 工厂 + 全调用点补参（`first.mode()` 或 `"STANDARD"`）+ 既有测试全绿（含 FlywayMigrationTest 验 V8）
- [ ] **Step 5** `mvn test` 全绿 → Commit `feat(modes): V8 会话模式列 + ChatMode 枚举 + Conversation.mode（全调用点）`

### Task 2: AgentFactory 三档构造分支 + facade 传参

**Files:**
- Modify: `agent/src/main/java/com/linagent/agent/agent/AgentFactory.java`
- Modify: `agent/src/main/java/com/linagent/agent/facade/AgentFacade.java`（chat/resume 读 conv.mode 传入）
- Test: `AgentFactoryTest` 扩展 + `AgentFacadeTest` 修复

**Interfaces:**
- `AgentFactory.create(AuthContext, Long conversationId, Sinks.Many, AtomicReference, ToolInterceptor, ChatMode mode)` 新尾参重载（旧签名委托 STANDARD，测试兼容）；返回的 `AgentHandle` 增字段 `ChatMode mode`（断言用）
- 构造分支：AUTO → hooks 不含 ApprovalHook（tools/技能/压缩照常）；CHAT → `tools(List.of())`（或 builder 省略 tools）+ hooks 仅 `List.of(skillsHook 不挂, shellHook 不挂, summarizingHook)`——**即 hooks 里只留 summarizingHook**，且 systemPrompt = 缓存串 + `"\n\n" + CHAT_SUFFIX`（常量在 AgentFactory，spec §2.3 原文）；STANDARD → 现状全挂
- AgentFacade：chat 与 resume 两处 `agentFactory.create(...)` 补 `ChatMode.parse(conv.mode())`

- [ ] **Step 1** AgentFactoryTest 失败测试（三档）：
```java
// AUTO: handle.agent() 构造成功（行为级断言难——断言 hooks 组合需暴露；改断言 AgentHandle.mode() + 
//      CHAT: systemPrompt 含 "纯对话模式"；STANDARD: mode 正确）
// 最小可测：AgentHandle 携带 mode + systemPrompt；CHAT prompt 含追加句；三档 create 不抛
```
- [ ] **Step 2** RED → 实现（分支 + CHAT_SUFFIX + 重载）→ GREEN → Commit `feat(modes): AgentFactory 三档构造——AUTO 免审批 / CHAT 无工具+prompt 声明 / facade 传参`

### Task 3: PUT /mode 端点 + 列表带 mode

**Files:**
- Modify: `web/src/main/java/com/linagent/web/controller/ConversationController.java`（PUT + 列表/详情响应加 mode）
- Modify: 会话响应 DTO（找到 ConversationResponse 所在，加 mode 字段）
- Test: `ConversationControllerTest` / `AuthContextFilterTest`（切片补桩）

**Interfaces:**
- `PUT /api/conversations/{id}/mode` body `{"mode":"AUTO"}` → 200 `{id, mode}`；非法枚举 400；归属 404；`turns.existsByConversationIdAndStatus(id,"WAITING_APPROVAL")` → 409（复用 ApprovalPendingException 或专用异常映射）
- 列表 `GET /api/conversations` 元素与创建响应加 `mode` 字段
- 落库：`conversations.save(new Conversation(conv.id(), conv.title(), conv.threadId(), conv.compactSummary(), conv.compactedTurnSeq(), conv.tenantId(), conv.userId(), conv.mode(), conv.createdAt(), Instant.now()))`——注意 mode 字段位置（第 10 参）

- [ ] **Step 1** 失败测试（PUT 三档/400/404/409、列表含 mode）→ RED → 实现 → GREEN → Commit `feat(modes): PUT /mode 端点（枚举校验/pending 409）+ 会话响应带 mode`

### Task 4: 前端切换器 + 回归

**Files:**
- Modify: `frontend/src/App.vue`（头部三段切换器 + 纯聊 placeholder）、`frontend/src/api/rest.ts`（putConversationMode + ConversationSummary.mode）
- Modify: `frontend/src/types.ts`（ConversationSummary 加 mode）

**Interfaces:**
- 切换器：当前会话 mode 高亮三段（🔓 自由-红 / 🛡 标准 / 💬 纯聊）；点击 → `putConversationMode(convId, mode)` → 本地 `conversations` 项更新；409 时提示「有待审批操作，先决议再切换」
- 纯聊档激活时输入框 placeholder「纯对话模式（无工具）」
- vitest：切换请求体 + 模式状态映射（若纯函数 seam 可测；否则 Playwright 手测记录）

- [ ] **Step 1** rest.ts + types + vitest（模式码→图标/文案映射纯函数）→ 绿 → Commit
- [ ] **Step 2** App.vue 切换器 + placeholder + 409 提示 → vitest + build 绿 → Commit `feat(modes): 前端三段模式切换器（自由红警/纯聊占位提示）`

### Task 5: E2E 三档行为 + 全量回归

**Files:**
- Test: `agent/src/test/java/com/linagent/agent/modes/ChatModesEndToEndTest.java`（参照 ApprovalEndToEndTest 容器/桩模式）

- [ ] **Step 1** 三测：
```java
// AUTO：seed conv(mode=AUTO) → chat "RUN:mkdir auto_demo" → 断言无 ApprovalRequest、工具直执行、TurnDone
// CHAT：seed conv(mode=CHAT) → 桩模型（会尝试 toolCall 的脚本模型）→ 断言模型收到的 tools 为空/
//       无 tool_call 事件、prompt 含"纯对话模式"、纯文本 TurnDone
// STANDARD：既有 ApprovalEndToEndTest 已覆盖（回归即可）
```
- [ ] **Step 2** 全量 `mvn test && cd frontend && npx vitest run && npm run build` → Commit `test(modes): E2E 三档行为——AUTO 直执行/CHAT 无工具+prompt 声明`
