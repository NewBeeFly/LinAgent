# LinAgent 工具审批（HITL Approval）设计文档

- 日期：2026-09-23
- 状态：已与需求方逐节确认（命令级粒度 / 用户级持久化+session 双作用域 / 409 模态 / 方案 B）
- 路径：Architectural（新子系统：贯穿 agent 执行中断、规则引擎、SSE 协议、前端交互）
- 后续：本文档确认后，经 superpowers:writing-plans 产出实施计划，分支 `feat/approval-hitl` 迭代

## 1. 背景与目标

shell 工具无 OS 级沙箱（CLAUDE.md 已知边界）下的唯一现实防线：**工具调用前人工审批**。
沙盒是高成本的技术隔离（多租户生产化前置），审批是低成本的 harness 强制把关——单机
学习场景下 90% 的价值、20% 的成本，且为后续 task 拆分（子任务自动跑命令）铺安全底座。

### 目标

- `write_file` / `shell` 调用按**命令级规则**判定：白名单/已授权规则放行，其余中断等待人工审批
- 中断态持久化（checkpoint），刷新/重启后审批卡片与恢复能力完整自洽
- 审批记忆双作用域：session（内存，会话内有效）/ forever（按 tenant+user 落库，跨会话）
- 拒绝（含理由）作为 tool result 回传模型，模型据此调整方案（对齐 Claude Code deny+message 语义）

### 非目标（本期不做）

- 模式层（default/acceptEdits/dontAsk 切换）与 deny/ask 规则分层（数据模型预留 effect 列）
- OS 级沙盒（多租户生产化时再做；规则层不承诺安全边界——文本规则可被 `/bin/rm`、`bash -c` 绕过，标杆文档原话 "For filesystem and network enforcement that doesn't depend on the command text, use sandboxing"）
- 审批转 Slack/邮件等外部审批流
- 挂起期间新消息的「自动拒绝放行」（409 模态挡回，二期再议）

## 2. 调研结论（2026-09-23 实证，非猜测）

| # | 结论 | 依据 |
|---|---|---|
| 1 | SAA 1.1.2.3 有完整 HITL 基建：`HumanInTheLoopHook`（`approvalOn` 按工具名）、`InterruptionMetadata`（per-toolCall 的 APPROVED/REJECTED/EDITED + reason）、feedback 经 `RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY` 传递、需 checkpointer（已有 PostgresSaver）；`validateFeedback` 校验**全部** toolCalls 有 feedback 才放行（整批语义是框架硬约束） | sources jar `hook/hip/*` + `action/InterruptableAction` 解包 |
| 2 | SAA hook 仅支持工具名级粒度，命令级判定需自写（抄其骨架）——选定方案 B | 同上（`approvalOn` 为 Map<toolName, ToolConfig>，无扩展点） |
| 3 | Claude Code 标杆：六步评估管道（hook→deny→ask→模式→allow→HITL 回调）首个命中短路；只读默认放行/写必问/命令前缀规则+复合命令拆段匹配；"don't ask again" 分级持久化；deny+message 回传为 tool result（有理由模型继续换路，无理由终止 turn）；规则由 harness 强制而非模型自觉（"Permission rules are enforced by Claude Code, not the model"）；通配符 `*` 语义的历史包袱自承认 | code.claude.com permissions.md / permission-modes.md / hooks.md / agent-sdk 文档（本会话实抓） |
| 4 | Codex CLI 三档模式（suggest/auto-edit/full-auto）+ `canAutoApprove` 决策 + 与沙箱协同 | GitCode/腾讯云开发者社区文章 |
| 5 | 交互模型：审批发生在工具调用间隙，对模型透明——批准则工具真实执行、拒绝则理由作为该次 tool result 回传；审批不是模型交互，模型从不被询问 | 设计期间与需求方确认 |

## 3. 方案选择记录

- **B（选定）**：自写 `ApprovalHook`（抄 SAA HITL 骨架，命令级判定层）+ SAA 原生中断/恢复机制。
  拿到 checkpoint 持久化中断、ToolFeedback 回传、edit/reject 三态全部原生能力。
- **C（退路）**：ToolInterceptor + CountDownLatch 双请求挂起。中断态不进 checkpoint（刷新即丢）、
  占死线程。仅当实施 spike 证明 SAA resume 路径走不通时启用，架构其余部分不变。
- 排除：SAA `HumanInTheLoopHook` 直接用（工具级粒度不满足需求）。

## 4. 架构

```
模型发起 tool_calls（write_file / shell）
  → ApprovalHook.interrupt()（挂工具节点前，InterruptableAction）
      ① 内置只读命令白名单（硬编码常量）命中 → 放行
      ② 规则匹配：session 作用域（内存）→ user 作用域（permission_rule 表）→ 命中放行
      ③ 未命中 → 中断（SAA 原生：checkpoint 存档）
           ├─ 待审批 TOOL_CALL 行立即落 message 表（展示层留痕 + pending 详情数据源）
           ├─ AgentEvent.ApprovalRequest（流尾事件，之后不发 TurnDone）
           └─ turn.status = waiting_approval
用户在卡片上决策（一次 POST 携带整批 per-toolCall 决策）
  → POST /conversations/{id}/approvals
      ├─ remember=forever → 规则落 permission_rule；session → 内存 Map
      ├─ 构建 InterruptionMetadata（APPROVED/REJECTED+reason）
      └─ feedback 进 RunnableConfig metadata → 续流 → 新 SSE → 前端按 turnId 合并
```

组件清单：

| 组件 | 职责 | 变更 |
|---|---|---|
| `PermissionRuleEngine`（新，agent） | 匹配语义：白名单/前缀段通配/复合命令拆段/路径前缀/作用域评估序 | 新增 |
| `ApprovalHook`（新，agent，抄 SAA 骨架） | 中断判定（调规则引擎）+ feedback 校验与放行 | 新增 |
| `permission_rule` 表（Flyway V6）+ 仓库 | forever 作用域规则存储 | 新增 |
| session 规则 | 内存 Map<(tenant,user,convId), Set<rule>>，会话生命周期 | 新增 |
| `AgentFacade` | chat 前置 409 检查；新增 `resume()` 入口；中断路径跳过 TurnDone 拼接 | 改 |
| `AgentEvent` | 新增 `ApprovalRequest`（turnId、待审批项数组、判定明细、建议规则） | 改 |
| turn 状态 | 新值 `waiting_approval` | 改 |
| 前端 | 审批卡片、409 横幅、设置页、turn.ts 合并 | 改 |
| system-prompt.md | 一行审批预期措辞 | 改 |

## 5. 规则引擎

### 数据模型（V6__permission_rule.sql）

```sql
CREATE TABLE permission_rule (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR NOT NULL,
  user_id VARCHAR NOT NULL,
  tool_name VARCHAR NOT NULL,     -- 'shell' / 'write_file'
  pattern VARCHAR NOT NULL,       -- '*'（工具级）或命令/路径前缀（'pip install *'）
  effect VARCHAR NOT NULL,        -- MVP 仅 'ALLOW'；deny/ask 值预留二期
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, tool_name, pattern)
);
```

### 匹配语义

- `pattern='*'`：该工具全部调用放行
- shell 命令前缀：`*` 尾缀**只做空格分隔的段匹配**（`pip install *` 匹配 `pip install pandas`，
  不匹配 `pip installx`——收紧 Claude Code 自承认的通配符历史包袱）
- **复合命令拆段**：按 `&& / || / ; / |` 拆子命令，**全部子命令各自命中 allow 才整体放行**，
  任一未命中整体上升审批（防 `ls && rm -rf /` 混渡）
- `write_file`：pattern 匹配目标路径前缀（`reports/*`）
- **评估序固定**：内置白名单 → session 规则 → user 规则 → 审批。设计为可插拔，二期 deny 层
  插最前即天然满足 deny-first

### 默认策略

- `read_file / list_dir / csv_summary / read_skill`：放行（已有越界校验兜底）
- `write_file / shell`：必问
- shell 内置只读命令白名单（硬编码，不可配置）：`ls cat pwd head tail grep find wc which diff
  stat du echo cd git status git log git diff git show python --version python3 --version`

## 6. 中断与恢复协议

### 事件与状态语义（需求方确认的修正）

- **ApprovalRequest 是流尾事件**：中断路径下它之后流 complete，**不发 TurnDone**——TurnDone
  保持纯终态语义（COMPLETED/FAILED），`waiting_approval` 是 turn 表状态列的值，不是 TurnDone
  的子状态。AgentFacade 中断路径跳过 TurnDone 拼接（与 CANCEL 的 once-guard 同模式）
- **TOOL_CALL 提前落库**：中断时立即把待审批 tool_calls 落 message 表（seq 照常）；批准后
  执行再落 TOOL_RESULT——pending 详情查展示表、历史回放完整留痕（含「经审批」轨迹）
- 载荷：`ApprovalRequest(turnId, items[{toolName, arguments, commandText, subCommandVerdicts[],
  suggestedRule}], conversationId)`；suggestedRule 为「批准并记住」将生成的规则预览（可编辑）

### resume

- `POST /api/conversations/{id}/approvals`，体：`{items:[{callId, decision: approve|reject,
  reason?}], remember: once|session|forever}`——**整批提交**（SAA validateFeedback 硬约束：
  全部 toolCalls 有 feedback 才放行，部分提交会再次中断）
- 响应为**新 SSE 流**（续流事件直至下一中断或轮次完成），前端按 turnId 合并进原 turn
- remember 写入：once 不记 / session 内存 Map / forever 落 permission_rule

### 协议分流与挂起期策略（409 模态）

- `POST /chat` 前置检查：会话存在 `waiting_approval` turn → **409 + pendingApproval 详情**
- 前端：横幅提示 + 滚动到卡片，不锁输入框（发送被 409 挡回，后端兜底）
- `GET /conversations/{id}/approvals`：刷新/重进后拉 pending 详情（从 message 表 pending
  TOOL_CALL 行构建，判定明细实时重跑规则引擎）
- approvals 端点幂等保护：重复提交同一 pending → 409

### 拒绝语义

- REJECTED + reason 作为该次 tool result 回传（SAA ToolFeedback 原生通道），模型自然收尾
  （换路子或道歉结束）
- **不做** Claude Code 的「无理由拒绝终止轮」特判——resume 后图自然走完，行为同样可控、实现少一路

## 7. 刷新恢复链（自洽性，三层持久化互相印证）

```
重进会话 → GET /turns → turn.status=waiting_approval（存在性）+ 已落库 TOOL_CALL 行（内容）
→ GET /approvals → 服务端构建详情 → 渲染卡片（与实时事件同组件同数据结构）
→ 提交 → POST /approvals → checkpoint 恢复续流（可恢复性）
```

存在性（turn 状态列）、内容（message 表）、可恢复性（checkpoint）全在 PostgreSQL——刷新、
换浏览器、服务重启全部自洽，不依赖内存/连接/前端存储。等待中的 turn 渲染为「等待审批中」
挂起态；审批完成后的回放时间线为 `TOOL_CALL → TOOL_RESULT`（可标「经审批」角标）。

## 8. 前端

- **审批卡片**（复用工具卡片样式位）：逐项展示命令/路径 + 子命令判定明细（看得见「为什么问」）
  + 四动作（批准 / 拒绝+理由 / 批准并不再询问·本会话 / 批准并永久允许·附 suggestedRule 可编辑）
  + 底部「全部批准 / 全部拒绝」；单工具场景退化为单条直接决策
- **设置页** `/settings/permissions`：规则表格（工具/模式/作用域/来源），用户规则可增删改
  （`GET/POST/DELETE /api/permission-rules`，按当前身份过滤），内置白名单只读展示
- `turn.ts`：ApprovalRequest 事件分区 + 两段流按 turnId 合并（复用 callId 合并底子）

## 9. system prompt 措辞（唯一模型侧配合，非机制依赖）

system-prompt.md 增加一句：「工具调用可能触发人工审批：被拒绝时你会收到含理由的拒绝结果，
请据此调整方案或征询用户意见。」

## 10. 边界与并发

- 一次中断含多个待审批调用：整批决策一次提交（§6）；全部批准/拒绝快捷键
- 同会话并发：409 已覆盖 chat 侧；approvals 幂等 409
- 会话删除：pending 审批随 turn/message 删除，checkpoint 走 CheckpointCleaner 现有路径
- 多租户：规则按 (tenant_id, user_id) 隔离，与 conversation 同粒度

## 11. 测试策略

- **规则引擎单测**（重头，匹配语义矩阵）：前缀/段通配边界（`pip install *` ≠ `pip installx`）、
  复合命令拆段全命中才放行、write 路径前缀、白名单、作用域评估序、`*` 工具级
- **中断/恢复集成**（Testcontainers + 桩模型）：真图跑至中断 → 断言 ApprovalRequest 事件 +
  checkpoint 存档 + TOOL_CALL 落库 → resume 批准 → 断言工具真实执行、结果进模型输入；
  resume 拒绝 → 断言拒绝文案作为 tool result 回传；整批多工具各决策组合
- **协议测试**：409 语义（waiting_approval 存在时 chat 挡回）、approvals 幂等 409、
  remember 三档写入位置（once 无痕 / session 内存 / forever 落库）
- **刷新恢复**：中断后重查 GET /turns + GET /approvals 断言卡片数据完整

## 12. 实施顺序（供 writing-plans 展开）

1. 分支 `feat/approval-hitl`；**spike 打头**：钉死「中断时 AgentFacade Flux 的信号形态」与
   「resume 穿透 AgentFactory 每轮新建 config 的准确方式」（产出=可运行的集成测试骨架）；
   SAA 缺口则落方案 C 退路，其余设计不变
2. 规则引擎 + V6 迁移（TDD：匹配矩阵先行）
3. ApprovalHook + 中断路径（事件/turn 状态/TOOL_CALL 提前落库/TurnDone 跳过）
4. resume 端点 + remember 三档 + 409 前置
5. 前端：卡片 / 409 横幅 / turn.ts 合并
6. 设置页 + system prompt 措辞 + 全量回归
