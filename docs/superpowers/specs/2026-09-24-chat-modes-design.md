# LinAgent 会话模式（Chat Modes）设计文档

- 日期：2026-09-24
- 状态：已与需求方确认（三档 / 完全无工具 / 会话级持久）
- 路径：Architectural（V8 迁移 + AgentFactory 构造分支 + 跨前后端契约），体量约为审批迭代的 1/4
- 后续：superpowers:writing-plans → SDD，分支 `feat/chat-modes`

## 1. 目标与非目标

三档会话模式，前端聊天界面小型切换器循环切换，下一轮生效：

| 模式 | 码 | 语义 | AgentFactory 构造差异 |
|---|---|---|---|
| 自由 🔓 | `AUTO` | 工具直执行，零审批 | **不挂 ApprovalHook**（压缩/技能 hook 照常） |
| 标准 🛡 | `STANDARD` | 规则判定 + 未授权弹卡（现状默认） | 现状（挂 ApprovalHook） |
| 纯聊 💬 | `CHAT` | 模型完全无工具，纯对话 | **tools 空 + 不挂 approval/skills/shell hook**（仅留压缩） |

### 非目标
- 全局（跨会话）模式记忆、模式切换历史
- deny/ask 规则分层（独立迭代）
- 进行中轮次的即时切换（下一轮生效即可）

## 2. 关键设计决策（已核实）

1. **存储**：conversation 表加 `mode VARCHAR NOT NULL DEFAULT 'STANDARD'`（Flyway V8）；Conversation record 尾部加 `mode` 字段 + `create` 工厂默认 STANDARD。所有 `new Conversation(...)` 调用点补参（ConversationController 两段式落库、InMemory 测试 double）。
2. **空 tools 合法性**（SAA 源码核实）：ReactAgent 构造含 `hasTools = toolCallbacks != null && !isEmpty()` 标志位，空列表为设计内支持（无工具节点路由），CHAT 模式直接 `.tools(List.of())` 或不传。
3. **CHAT 的 system prompt**：ResidentPromptBuilder 缓存串为静态（含工具说明），CHAT 模式在 AgentFactory **追加一句**：「当前为纯对话模式，无任何工具可用，请直接回答，不要声称会执行操作」——防模型口头承诺做事（prompt 声明有工具而协议层无 tools 定义，模型可能口头应承）。
4. **生效链**：`AgentFacade.chat/resume` 读 `conv.mode` → `AgentFactory.create(..., ChatMode mode)`（新尾参，重载保持旧签名兼容测试）→ 构造分支。进行中的轮不受影响。
5. **切换 API**：`PUT /api/conversations/{id}/mode` body `{mode}`——校验枚举（非法 400）、归属校验、**存在 WAITING_APPROVAL 轮时 409**（先决议再切，避免模式与 pending 恢复语义纠缠）。会话列表/详情响应带 `mode`（前端切换器初始态）。
6. **AUTO 视觉警示**：切换器自由档红色/⚠（前端层）。

## 3. 前端

- 聊天头部三段切换器（🔓 自由 / 🛡 标准 / 💬 纯聊）：点击 PUT + 本地即时高亮；当前档高亮、自由档红色
- 纯聊模式下输入框 placeholder「纯对话模式（无工具）」
- 新会话默认 STANDARD；切换成功后下一轮生效（无需提示，行为自证）

## 4. 测试策略

- 单测：ChatMode 枚举/Conversation 工厂默认值/AgentFactory 三档构造差异（hook 挂载与 tools 数量断言）/CHAT prompt 追加句
- API 测试：PUT mode（合法三档/非法 400/跨租户 404/pending 轮 409）/列表响应带 mode
- E2E（Testcontainers + 桩模型）：AUTO 下非白名单命令直执行不中断；CHAT 下模型输出纯文本（无 tool_call 事件）；STANDARD 照旧中断（既有 E2E 回归）
- 前端：切换器交互 + vitest（模式状态/切换请求体）

## 5. 实施顺序（供 writing-plans）

1. 分支 `feat/chat-modes` + V8 迁移 + ChatMode 枚举 + Conversation.mode（含全部构造点）
2. AgentFactory 三档构造分支 + facade 传参 + CHAT prompt 追加
3. PUT /mode 端点 + 列表响应带 mode + 409 语义
4. 前端切换器 + placeholder + 回归
