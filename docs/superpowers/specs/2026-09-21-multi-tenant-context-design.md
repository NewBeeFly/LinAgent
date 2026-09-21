# LinAgent 多租户/用户上下文与工作区隔离 设计文档

- 日期：2026-09-21
- 状态：已与需求方逐节确认（工作区布局 / 用户校验 / 数据隔离范围 / 上下文传递机制 四项决策均经选项确认）
- 路径：Architectural（横切 web 过滤器、DB schema、agent 工作区解析与每轮装配）
- 后续：本文档确认后，经 superpowers:writing-plans 产出实施计划

## 1. 背景与目标

v0.1.0（tag 已打）是单用户本地个人助手。本期引入**租户/用户两级身份**，为后续多用户、登录态、权限体系打地基，但刻意轻量实现：

- 请求头 `x-tenant-id` / `x-user-id` 携带身份，web 层 Filter 校验（app_user 表命中才放行，未知 → 401）
- 抽象 `RequestAuthenticator` 接口作为**唯一扩展点**（后续登录/token 换实现，本期只做 header + 用户表）
- 租户级与个人级**工作区隔离**：个人根为工具沙箱，租户共享区经 `shared` 符号链接可达
- 租户/用户上下文经 **ThreadLocal 边界运输**，供工具装配与数据隔离消费
- conversation 对话数据同步隔离（加归属列 + 查询过滤）

### 非目标（本期不做，防需求膨胀）

- 登录端点、token、密码（`RequestAuthenticator` 留扩展点即可）
- 前端用户切换 UI（默认身份走 env 配置；双用户验证走 curl）
- `shared/` 权限细分（所有成员可读写）
- turn/message/checkpoint 加租户列（随 conversation 级联隔离）
- 用户管理端点（新增用户手工 SQL）

## 2. 关键决策记录（头脑风暴确认）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 工作区布局 | 个人根 + shared 挂载 | `resolveSafely` 的 `normalize()` 纯词法不解析链接，根内 symlink 天然通过越界校验，零解析代码改动；个人/租户区均有真实语义 |
| 用户校验 | 预置用户表 + 拒绝未知 | 干净可测无脏数据；Flyway 建表 + 种子数据，未知 (tenant,user) → 401 |
| 对话数据 | 同步隔离 | conversation 加 tenant/user 列 + 查询过滤，隔离彻底 |
| 上下文传递 | ThreadLocal 边界运输（方案 A） | 符合既定方向；web 入口 set、agent 入口同步段读一次、其余显式传参；`facade.chat` 对外签名不变 |

## 3. 组件与归属

```
web 模块
├── AuthContextFilter          Jakarta Filter（order 最高）：读 header → RequestAuthenticator
│                              校验 → AuthContextHolder.set；try-finally clear；失败 401 JSON
├── RequestAuthenticator       鉴权抽象接口：Optional<AuthContext> authenticate(tenantId, userId)
└── HeaderUserAuthenticator    实现：查 AppUserRepository 命中才通过（唯一本期实现）

agent 模块（不依赖 web 的原则不变；AuthContext 是业务上下文非 HTTP 概念）
├── context/AuthContext        record(tenantId, userId, name)
├── context/AuthContextHolder  ThreadLocal：set / get / clear / require（缺失抛 IllegalStateException）
├── workspace/WorkspaceResolver resolve(tenantId, userId) → 个人根 Path
│                              ensure() 幂等 provision：建 {tenant}/shared、{tenant}/users/{user}、
│                              个人根内 shared 符号链接 → ../../shared
├── persistence/AppUser + AppUserRepository（JDBC，归 agent persistence 包，web 已 scan）
├── AgentFactory               create(ctx) 接收显式上下文（facade.chat 同步段从 Holder 读出后传入）→
│                              WorkspaceResolver → FileTools / ShellTool2 / CsvSummaryTool
│                              改为每轮实例（烤入个人根）
└── ConversationRepository     创建时归属 ctx；列表/详情/chat/删除一律带 (tenantId, userId) 条件

frontend
└── api/rest.ts + api/sse.ts   统一注入默认 header（VITE_TENANT_ID / VITE_USER_ID，
                               缺省 default/linmj）；401 简单提示
```

**结构性改动**：`FileTools` 从单例 Bean 退场，变为每轮构造（`AgentBeansConfig.fileTools` Bean 删除）。
`agent.workspace-root` 语义升级为"工作区基根"，个人根 = `{基根}/{tenant}/users/{user}`，仍经
`ProjectPathResolver.resolveDir` 上溯解析（v0.1.0 修复的坑，不回归）。

## 4. 数据模型（Flyway V4__multi_tenant.sql）

```sql
CREATE TABLE app_user (
  tenant_id  VARCHAR(64)  NOT NULL,
  user_id    VARCHAR(64)  NOT NULL,
  name       VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, user_id)
);
INSERT INTO app_user (tenant_id, user_id, name) VALUES
  ('default', 'linmj', '林同学'),
  ('default', 'tester', '测试');

ALTER TABLE conversation ADD COLUMN tenant_id VARCHAR(64),
                         ADD COLUMN user_id   VARCHAR(64);
UPDATE conversation SET tenant_id = 'default', user_id = 'linmj';
ALTER TABLE conversation ALTER COLUMN tenant_id SET NOT NULL,
                         ALTER COLUMN user_id   SET NOT NULL;
CREATE INDEX idx_conversation_tenant_user ON conversation (tenant_id, user_id);
```

turn/message/checkpoint 不动：threadId 前缀 `conv-{id}` 随 conversation 天然隔离，删除链路（CheckpointCleaner）不受影响。

## 5. 请求时序与 ThreadLocal 纪律

以 `POST /api/conversations/{id}/chat`（SSE）为例：

1. **Filter（请求线程）**：header 缺失或 app_user 未命中 → 401 JSON（不再进 DispatcherServlet）；
   命中 → `Holder.set`。**doFilter 开头先防御性 clear 一次**（双保险：即使上一请求异常残留也不串号）
2. **facade.chat() 方法体第一行（请求线程同步执行）**：`AuthContext ctx = AuthContextHolder.require()`
   ——**必须在 `Flux.defer` 外捕获，闭包带入 lambda**。红线依据（已核实现状代码）：`AgentFacade.chat()`
   现为 `Flux.defer(() -> {...})` 结构，会话校验与 `agentFactory.create()` 都在**订阅期**于 reactor 线程
   执行，届时 Filter finally 已清理、且不在同一线程——defer 内读 Holder 必炸。改造即：defer 外读一次，
   defer 内只用闭包变量
3. **Flux 订阅后（reactor 线程）**：工具执行用实例内路径；turn/message 落库随 conversation，不需要 ctx
4. **Filter finally clear**：正常/异常两路都清；异步 SSE 下 finally 早于流结束触发无碍（读取已锁死在第 2 步）

纪律一句话：**ThreadLocal 是边界运输工具——web 入口 set，facade 入口 defer 外读一次捕获进闭包，
其余显式传参；执行期（reactor 线程）零 ThreadLocal 依赖。**

ThreadLocal 清理清单（全部落地 + 测试守护）：

- Filter `try-finally` clear（异常路径同样覆盖）
- Filter 开头防御性 clear（线程池复用防残留）
- `Holder.require()` 缺失即抛 ISE——任何越界读取第一时间暴露而非静默串号
- 守护测试：FilterTest 断言请求结束后 `Holder.get() == null`；连续两次请求（第二次不带 header）
  第二次必须 401 而非继承首次身份（专防池化线程残留）

## 6. 工作区布局与解析

```
{基根}/default/shared/                              ← 租户共享（真实目录）
{基根}/default/users/linmj/shared -> ../../shared   ← 幂等自动挂载
{基根}/default/users/linmj/data.csv                 ← 个人文件
```

- 工具根（read_file/write_file/list_dir/csv_summary/shell cwd）= 个人根
- `read_file("shared/团队规范.md")` ✅（symlink 在根内，词法 normalize 后仍在根内，OS 层跟随链接）
- `read_file("../zhangsan/data.csv")` ❌（normalize 后越界，`resolveSafely` 拒绝）
- provision 幂等：目录已存在不重建；shared 链接缺失才建（相对路径链接，基根整体搬迁不断链）
- 现存 `workspace/`（空）与 `web/workspace/`（bug 残留，空）不迁移——首次带身份请求时在新结构下重建

## 7. 错误处理

### 7.1 后端语义

| 场景 | 行为 |
|---|---|
| 缺 header / app_user 未命中 | 401，JSON body 说明缺什么 |
| conversation 不存在或不属于当前 (tenant,user) | 404（不泄漏存在性），chat 与 CRUD 一致 |
| WorkspaceResolver provisioning IO 失败 | 500，异常信息带目标路径 |
| Holder.require() 在无上下文时被调用（内部误用） | IllegalStateException，500（设计缺陷信号，测试守护） |

### 7.2 前端兼容（用户无感知红线：禁止裸 404/401 透出）

现状（已核实）：`rest.ts`/`sse.ts` 统一 `throw Error("HTTP 404")`，`deleteConversation` 不检查
`resp.ok`，401/404 对用户是天书。本期补齐：

- **类型化错误**：`rest.ts`/`sse.ts` 抛 `ApiError{status, message}`（携带后端 JSON message），
  替代裸 `HTTP xxx`
- **401（身份无效）**：全局提示"当前身份（{tenant}/{user}）未注册或已失效，请检查
  VITE_TENANT_ID / VITE_USER_ID 配置"——env 身份无登录可跳，指向配置是唯一出路
- **404（会话不可用）场景化处理**：
  - `getTurns` 404 → 提示"该会话不存在或无权访问"，自动从侧栏移除该会话，回到欢迎面板
  - chat 404 → 当前 turn 展示"该会话已不可用（可能已删除或归属其他用户）"，并刷新会话列表
  - `deleteConversation` 404 → **幂等成功**处理：视为已删除，直接移除列表项（不报错）
- 404 高发于切换身份后旧列表残留 / 会话被并发删除 / 深链直达——列表本身已按身份过滤，
  上述兜底保证边缘路径不裸奔
- 前端单测（vitest 已有基建）：ApiError 分类、404 分支文案

## 8. 测试

- **agent 单测**
  - `WorkspaceResolverTest`：幂等（二次 ensure 不变）、shared 链接指向正确、个人根互相不可达
    （与 `FileTools.resolveSafely` 集成断言）、相对链接语义
  - `AuthContextHolderTest`：set/get/clear/require 语义
- **web 单测**
  - `AuthContextFilterTest`：无 header 401、未知用户 401、合法放行（Holder 有值且 finally 后清理）
  - `ConversationIsolationTest`：linmj 建的会话，tester 列表不见/详情 404/chat 404/删除 404；
    linmj 自己全通
  - 既有 WebTestClient 全局补默认 header（测试基类 mutate 或静态默认值）
- **manual 冒烟组**：SkillsSmokeTest 请求补默认 header；双用户工作区隔离 curl 实证（两个身份各自
  write_file，互不可见，shared 互通）

## 9. 前端改动（最小）

- `api/rest.ts` / `api/sse.ts`：统一注入 `x-tenant-id` / `x-user-id`（`import.meta.env.VITE_TENANT_ID`
  ?? 'default'，`VITE_USER_ID` ?? 'linmj'）
- 401 响应：简单错误提示（不做登录跳转，无登录可跳）
- `.env.example` 补两个变量说明
