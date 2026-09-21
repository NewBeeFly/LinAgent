# 多租户/用户上下文与工作区隔离 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 LinAgent 引入租户/用户身份（请求头校验 + ThreadLocal 边界运输）、租户/个人两级工作区隔离、conversation 数据归属隔离，前端补 404/401 无感兼容。

**Architecture:** web 层 Filter 读 `x-tenant-id`/`x-user-id` 经 `RequestAuthenticator`（本期唯一实现：查 app_user 表）校验后 set `AuthContextHolder`（ThreadLocal）；`AgentFacade.chat()` 在 **Flux.defer 外**读一次捕获进闭包；工具实例（FileTools/ShellTool2/CsvSummaryTool）从单例 Bean 改为每轮构造并烤入个人工作区根 `{基根}/{tenant}/users/{user}`（根内 `shared` 符号链接指向租户共享区）。

**Tech Stack:** JDK21 / Spring Boot 3.5.3 / Spring AI 1.1.2 / SAA 1.1.2.3 / Spring Data JDBC + Flyway + PostgreSQL / Vue3 + Vite + TS + vitest

**Spec:** `docs/superpowers/specs/2026-09-21-multi-tenant-context-design.md`（本计划从 spec 出发，执行者两个文件都读）

## Global Constraints

- 依赖栈坑（CLAUDE.md 实证清单，勿凭记忆推翻）：`ToolCallbacks.from()` 不存在，用 `MethodToolCallbackProvider`；`AssistantMessage` 用 builder；`agent.stream()` 抛受检 `GraphRunnerException`
- 迁移脚本一律放 `agent/src/main/resources/db/migration/`（web 的 Flyway 与 agent 测试的 classpath 都从这里读）
- `agent` 模块改动后必须 `mvn -pl agent install -DskipTests` 再起 web；`spring-boot:run` **禁止加 `-am`**
- ThreadLocal 纪律：只有 AuthContextFilter 能 `set`；`require()` 只允许出现在 controller 方法体与 `facade.chat()` 的 **defer 外**同步段；reactor 线程零 ThreadLocal 依赖
- 相对路径配置（`agent.workspace-root` 等）必须经 `ProjectPathResolver.resolveDir`，直接 `Path.of(相对路径)` 是回归（v0.1.0 已修的坑）
- 用户可见文案一律中文；测试用 JUnit5 + AssertJ + Mockito（web 切片测试用 @WebMvcTest + @MockBean，DB 测试用 Testcontainers `postgres:16-alpine` + `@ServiceConnection`，容器需 `withUrlParam("stringtype","unspecified")`）
- @WebMvcTest 会自动装配 Filter 类型的 Bean——AuthContextFilter 落地后，所有 @WebMvcTest 切片必须补 `@MockBean RequestAuthenticator` 并打桩，否则上下文起不来
- pom 默认排除 manual 分组；真实 API 冒烟只在显式 `-Dgroups=manual` 时跑
- 每个任务收尾：`mvn test` 全绿才 commit；commit 信息中文、feat/fix/docs 前缀

---

### Task 1: Flyway V4 迁移 + AppUser + AppUserRepository

**Files:**
- Create: `agent/src/main/resources/db/migration/V4__multi_tenant.sql`
- Create: `agent/src/main/java/com/linagent/agent/persistence/AppUser.java`
- Create: `agent/src/main/java/com/linagent/agent/persistence/AppUserRepository.java`
- Test: `agent/src/test/java/com/linagent/agent/persistence/AppUserRepositoryTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `AppUser record(tenantId, userId, name, createdAt)`；`AppUserRepository.findByTenantIdAndUserId(String,String) → Optional<AppUser>`（@Component，JdbcTemplate 实现——复合主键只读查询，沿用 CheckpointCleaner 的 @Component+JdbcTemplate 先例，不走 CrudRepository 派生）

- [ ] **Step 1: 写失败测试**

```java
package com.linagent.agent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJdbcTest(properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V4__multi_tenant.sql"
})
@Import(AppUserRepository.class)
@Testcontainers
class AppUserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    @Autowired
    AppUserRepository users;

    @Test
    void seedUsersQueryableByCompositeKey() {
        Optional<AppUser> linmj = users.findByTenantIdAndUserId("default", "linmj");
        assertThat(linmj).hasValueSatisfying(u -> {
            assertThat(u.name()).isEqualTo("林同学");
            assertThat(u.createdAt()).isNotNull();
        });
    }

    @Test
    void unknownUserIsEmpty() {
        assertThat(users.findByTenantIdAndUserId("default", "stranger")).isEmpty();
        assertThat(users.findByTenantIdAndUserId("other", "linmj")).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=AppUserRepositoryTest`
Expected: 编译失败（AppUserRepository 不存在）

- [ ] **Step 3: 写 V4 迁移与实现**

`agent/src/main/resources/db/migration/V4__multi_tenant.sql`：

```sql
-- v0.2 多租户：用户表（本期鉴权唯一数据源）+ conversation 归属列
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

`AppUser.java`：

```java
package com.linagent.agent.persistence;

import java.time.Instant;

/** 用户实体（app_user 表）。只读查询，无写入路径（新增用户手工 SQL）。 */
public record AppUser(String tenantId, String userId, String name, Instant createdAt) {
}
```

`AppUserRepository.java`：

```java
package com.linagent.agent.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * app_user 只读查询。复合主键 (tenant_id, user_id) 且仅查询无持久化，
 * 沿用 CheckpointCleaner 的 @Component + JdbcTemplate 先例，不走 CrudRepository。
 */
@Component
public class AppUserRepository {

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AppUser> findByTenantIdAndUserId(String tenantId, String userId) {
        List<AppUser> rows = jdbcTemplate.query(
            "SELECT tenant_id, user_id, name, created_at FROM app_user "
                + "WHERE tenant_id = ? AND user_id = ?",
            (rs, i) -> new AppUser(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()),
            tenantId, userId);
        return rows.stream().findFirst();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl agent test -Dtest=AppUserRepositoryTest`
Expected: `Tests run: 2, Failures: 0`（种子数据随 schema-locations 生效）

- [ ] **Step 5: 跑 agent 全量防回归，Commit**

Run: `mvn -pl agent test` → 全绿后：

```bash
git add agent/src/main/resources/db/migration/V4__multi_tenant.sql \
        agent/src/main/java/com/linagent/agent/persistence/AppUser.java \
        agent/src/main/java/com/linagent/agent/persistence/AppUserRepository.java \
        agent/src/test/java/com/linagent/agent/persistence/AppUserRepositoryTest.java
git commit -m "feat: app_user 表（V4）+ 只读用户查询，租户/用户身份数据源"
```

---

### Task 2: AuthContext + AuthContextHolder

**Files:**
- Create: `agent/src/main/java/com/linagent/agent/context/AuthContext.java`
- Create: `agent/src/main/java/com/linagent/agent/context/AuthContextHolder.java`
- Test: `agent/src/test/java/com/linagent/agent/context/AuthContextHolderTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `record AuthContext(String tenantId, String userId, String name)`；`AuthContextHolder.set(AuthContext)` / `get() → AuthContext|null` / `require() → AuthContext`（缺失抛 IllegalStateException）/ `clear()`——后续所有任务消费

- [ ] **Step 1: 写失败测试**

```java
package com.linagent.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthContextHolderTest {

    @AfterEach
    void clean() {
        AuthContextHolder.clear();
    }

    @Test
    void setThenGetThenClear() {
        AuthContext ctx = new AuthContext("default", "linmj", "林同学");
        AuthContextHolder.set(ctx);
        assertThat(AuthContextHolder.get()).isSameAs(ctx);
        assertThat(AuthContextHolder.require()).isSameAs(ctx);
        AuthContextHolder.clear();
        assertThat(AuthContextHolder.get()).isNull();
    }

    @Test
    void requireThrowsWhenMissing() {
        assertThatThrownBy(AuthContextHolder::require)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AuthContext");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=AuthContextHolderTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

`AuthContext.java`：

```java
package com.linagent.agent.context;

/**
 * 请求身份上下文（业务概念，非 HTTP 概念，故归 agent 模块）。
 * 由 web 层鉴权入口赋值，经 ThreadLocal 边界运输，入口同步段读出后显式传参。
 */
public record AuthContext(String tenantId, String userId, String name) {
}
```

`AuthContextHolder.java`：

```java
package com.linagent.agent.context;

/**
 * ThreadLocal 边界运输容器。纪律：只有鉴权入口（Filter）set；
 * require() 只允许出现在 controller 方法体与 facade.chat() 的 defer 外同步段；
 * reactor 线程零 ThreadLocal 依赖（订阅期清理已完成且线程不同，defer 内读取必炸）。
 */
public final class AuthContextHolder {

    private static final ThreadLocal<AuthContext> CTX = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    public static void set(AuthContext ctx) {
        CTX.set(ctx);
    }

    public static AuthContext get() {
        return CTX.get();
    }

    /** 缺失即抛：任何越界读取第一时间暴露而非静默串号 */
    public static AuthContext require() {
        AuthContext ctx = CTX.get();
        if (ctx == null) {
            throw new IllegalStateException(
                "AuthContext 缺失：未经鉴权入口，或在异步线程读取（ThreadLocal 仅入口同步段有效）");
        }
        return ctx;
    }

    public static void clear() {
        CTX.remove();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl agent test -Dtest=AuthContextHolderTest`
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/java/com/linagent/agent/context/ agent/src/test/java/com/linagent/agent/context/
git commit -m "feat: AuthContext + ThreadLocal 边界运输容器（纪律：仅入口 set/require）"
```

---

### Task 3: 鉴权链（Filter + RequestAuthenticator）+ 既有 web 测试适配

**Files:**
- Create: `web/src/main/java/com/linagent/web/auth/RequestAuthenticator.java`
- Create: `web/src/main/java/com/linagent/web/auth/HeaderUserAuthenticator.java`
- Create: `web/src/main/java/com/linagent/web/auth/AuthContextFilter.java`
- Create: `web/src/test/java/com/linagent/web/support/TestAuth.java`
- Test: `web/src/test/java/com/linagent/web/auth/AuthContextFilterTest.java`
- Modify: `web/src/test/java/com/linagent/web/controller/ChatControllerSseTest.java`、`web/src/test/java/com/linagent/web/controller/ConversationControllerTest.java`（补 @MockBean 打桩 + 默认身份 header）
- Modify: `web/src/test/java/com/linagent/web/smoke/SkillsSmokeTest.java`、`web/src/test/java/com/linagent/web/e2e/AgentEndToEndTest.java`（WebTestClient 默认身份 header；manual 组本轮不跑）

**Interfaces:**
- Consumes: Task 1 `AppUserRepository.findByTenantIdAndUserId`；Task 2 `AuthContext/AuthContextHolder`
- Produces: `RequestAuthenticator.authenticate(String tenantId, String userId) → Optional<AuthContext>`（后续扩展点）；`AuthContextFilter`（@Component @Order 最高，仅 `/api/*` 生效）；`TestAuth.LINMJ` / `TestAuth.TESTER`（`Consumer<HttpHeaders>`，后续 web 测试消费）

- [ ] **Step 1: 写失败测试**

`web/src/test/java/com/linagent/web/auth/AuthContextFilterTest.java`（探针接口用 ConversationController 列表端点；`@WebMvcTest` 自动装配 Filter 类型 Bean）：

```java
package com.linagent.web.auth;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.web.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(com.linagent.web.controller.ConversationController.class)
@AutoConfigureWebTestClient
@Import(AuthContextFilter.class)
@TestMethodOrder(OrderAnnotation.class)
class AuthContextFilterTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean RequestAuthenticator authenticator;
    @MockBean com.linagent.agent.persistence.ConversationRepository conversations;
    @MockBean com.linagent.agent.persistence.TurnRepository turns;
    @MockBean com.linagent.agent.persistence.MessageRepository messages;
    @MockBean com.linagent.agent.persistence.CheckpointCleaner checkpointCleaner;
    @MockBean com.linagent.agent.facade.AgentFacade agentFacade;

    @BeforeEach
    void stub() {
        when(authenticator.authenticate("default", "linmj"))
            .thenReturn(Optional.of(TestAuth.LINMJ_CTX));
    }

    @Test
    void missingHeaderRejected401() {
        webTestClient.get().uri("/api/conversations")
            .exchange().expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.message").isEqualTo("缺少身份请求头 x-tenant-id / x-user-id");
    }

    @Test
    void unknownUserRejected401() {
        when(authenticator.authenticate(any(), any())).thenReturn(Optional.empty());
        webTestClient.get().uri("/api/conversations")
            .header("x-tenant-id", "default").header("x-user-id", "stranger")
            .exchange().expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.message").isEqualTo("未知身份: default/stranger");
    }

    @Test
    @Order(1)
    void validIdentityPassesAndContextClearedAfter() {
        when(conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc(any(), any()))
            .thenReturn(List.of());
        webTestClient.get().uri("/api/conversations")
            .header("x-tenant-id", "default").header("x-user-id", "linmj")
            .exchange().expectStatus().isOk();
        // finally 清理：请求往返结束后无残留
        assertThat(AuthContextHolder.get()).isNull();
    }

    @Test
    @Order(2)
    void nextRequestWithoutHeaderDoesNotInheritPriorIdentity() {
        // spec §5 守护：紧随一次成功请求后，无 header 的请求必须 401（防池化线程残留串号）
        missingHeaderRejected401();
    }

    @Test
    void nonApiPathBypassesAuth() {
        when(authenticator.authenticate(any(), any())).thenReturn(Optional.empty());
        webTestClient.get().uri("/index.html")
            .exchange().expectStatus().isNotFound(); // 404（无此静态资源）而非 401
    }
}
```

> `findByTenantIdAndUserIdOrderByUpdatedAtDesc` 本任务先在 `ConversationRepository.java` 加方法声明（见 Step 3 末尾）；实体字段 Task 4 才补——本测试全程 mock，不触真实派生查询。

```java
List<Conversation> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);
```

（派生查询在实体无 tenantId/userId 字段时运行期才报错；本任务测试全程 mock，不触真实 SQL。Task 4 补实体字段后自然成立。）

同步在 `AgentFacadeTest` 无关——本任务不动 agent 主代码（仅此一处接口声明）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl web test -Dtest=AuthContextFilterTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现三个类**

`RequestAuthenticator.java`：

```java
package com.linagent.web.auth;

import com.linagent.agent.context.AuthContext;

import java.util.Optional;

/**
 * 鉴权抽象（本期唯一扩展点）：请求身份 → AuthContext。
 * 本期实现 HeaderUserAuthenticator（app_user 表校验）；
 * 后续登录态/token 换实现，Filter 与消费方不动。
 */
public interface RequestAuthenticator {

    Optional<AuthContext> authenticate(String tenantId, String userId);
}
```

`HeaderUserAuthenticator.java`：

```java
package com.linagent.web.auth;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.persistence.AppUserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** header 身份 + app_user 表校验（本期唯一实现） */
@Component
public class HeaderUserAuthenticator implements RequestAuthenticator {

    private final AppUserRepository users;

    public HeaderUserAuthenticator(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<AuthContext> authenticate(String tenantId, String userId) {
        return users.findByTenantIdAndUserId(tenantId, userId)
            .map(u -> new AuthContext(u.tenantId(), u.userId(), u.name()));
    }
}
```

`AuthContextFilter.java`：

```java
package com.linagent.web.auth;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 身份入口：/api/* 读取 x-tenant-id / x-user-id，经 RequestAuthenticator 校验后
 * 写入 ThreadLocal。401 短路不再进 DispatcherServlet。
 * 清理三保险：开头防御性 clear（防池化线程残留）+ finally clear + require() 兜底。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthContextFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "x-tenant-id";
    static final String USER_HEADER = "x-user-id";

    private final RequestAuthenticator authenticator;

    public AuthContextFilter(RequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuthContextHolder.clear();
        try {
            String tenantId = request.getHeader(TENANT_HEADER);
            String userId = request.getHeader(USER_HEADER);
            if (isBlank(tenantId) || isBlank(userId)) {
                writeUnauthorized(response, "缺少身份请求头 x-tenant-id / x-user-id");
                return;
            }
            Optional<AuthContext> authenticated = authenticator.authenticate(tenantId, userId);
            if (authenticated.isEmpty()) {
                writeUnauthorized(response, "未知身份: " + tenantId + "/" + userId);
                return;
            }
            AuthContextHolder.set(authenticated.get());
            filterChain.doFilter(request, response);
        } finally {
            AuthContextHolder.clear();
        }
    }

    private boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\": \"" + message + "\"}");
    }
}
```

`web/src/test/java/com/linagent/web/support/TestAuth.java`：

```java
package com.linagent.web.support;

import com.linagent.agent.context.AuthContext;
import org.springframework.http.HttpHeaders;

import java.util.function.Consumer;

/** web 测试默认身份（V4 种子数据） */
public final class TestAuth {

    public static final Consumer<HttpHeaders> LINMJ = h -> {
        h.add("x-tenant-id", "default");
        h.add("x-user-id", "linmj");
    };

    public static final Consumer<HttpHeaders> TESTER = h -> {
        h.add("x-tenant-id", "default");
        h.add("x-user-id", "tester");
    };

    public static final AuthContext LINMJ_CTX = new AuthContext("default", "linmj", "林同学");

    private TestAuth() {
    }
}
```

- [ ] **Step 4: 适配既有 web 测试（否则 Filter 一落地全部 401）**

@WebMvcTest 切片（Filter 类型 Bean 自动装配，`RequestAuthenticator` 不在切片内 → 需 @MockBean + 打桩）：

`ChatControllerSseTest.java` / `ConversationControllerTest.java` 各补：

```java
@MockBean com.linagent.web.auth.RequestAuthenticator authenticator;

@BeforeEach
void auth() {
    webTestClient = webTestClient.mutate().defaultHeaders(TestAuth.LINMJ).build();
    org.mockito.Mockito.when(authenticator.authenticate("default", "linmj"))
        .thenReturn(java.util.Optional.of(TestAuth.LINMJ_CTX));
}
```

（import 相应精简为项目风格；`webTestClient` 字段非 final 才能重赋值——已是 @Autowired 普通字段。）

全上下文测试（`SkillsSmokeTest` / `AgentEndToEndTest`，真实 DB + V4 种子 + 真实 HeaderUserAuthenticator）只补 header：

```java
@BeforeEach
void auth() {
    webTestClient = webTestClient.mutate().defaultHeaders(TestAuth.LINMJ).build();
}
```

（SkillsSmokeTest 的 `webTestClient.mutate().responseTimeout(...)` 已有 mutate 链，合并为一次：`.mutate().responseTimeout(Duration.ofSeconds(300)).defaultHeaders(TestAuth.LINMJ).build()`。）

ConversationControllerTest 若断言了 create/turns 的行为，mock 的 `findAllByOrderByUpdatedAtDesc` 调用点改为 `findByTenantIdAndUserIdOrderByUpdatedAtDesc`（控制器 Task 4 才切，本任务控制器未动、原方法仍被调用——**因此本步只需补 @MockBean + header，不动原打桩**；Task 4 再改）。

- [ ] **Step 5: 跑 web 测试确认全绿**

Run: `mvn -pl web test`
Expected: 全绿（含既有 ChatControllerSseTest / ConversationControllerTest / FlywayMigrationTest——FlywayMigrationTest 为全上下文，容器跑 Flyway 全量含 V4）

- [ ] **Step 6: Commit**

```bash
git add web/src/main/java/com/linagent/web/auth/ web/src/test/java/com/linagent/web/ \
        agent/src/main/java/com/linagent/agent/persistence/ConversationRepository.java
git commit -m "feat: 鉴权链——AuthContextFilter(401短路+三保险清理) + RequestAuthenticator 抽象 + header 用户实现"
```

---

### Task 4: conversation 归属隔离（实体/仓库/控制器/404 语义）

**Files:**
- Modify: `agent/src/main/java/com/linagent/agent/persistence/Conversation.java`
- Modify: `agent/src/main/java/com/linagent/agent/persistence/ConversationRepository.java`（补 `findByIdAndTenantIdAndUserId`）
- Create: `agent/src/main/java/com/linagent/agent/persistence/ConversationAccessDeniedException.java`
- Create: `web/src/main/java/com/linagent/web/controller/GlobalExceptionHandler.java`
- Modify: `web/src/main/java/com/linagent/web/controller/ConversationController.java`
- Modify: `agent/src/main/java/com/linagent/agent/compaction/CompactionService.java:100`（构造器对位补 tenantId/userId）
- Modify: `agent/src/test/java/com/linagent/agent/persistence/RepositoryTest.java`（实体构造对位 + schema-locations 补 V4 + 归属查询用例）
- Modify: `web/src/test/java/com/linagent/web/controller/ConversationControllerTest.java`（打桩方法切换）
- Test: `web/src/test/java/com/linagent/web/controller/ConversationIsolationTest.java`

**Interfaces:**
- Consumes: Task 2 `AuthContextHolder.require()`；Task 3 `TestAuth`
- Produces: `Conversation.create(title, threadId, tenantId, userId, now)`；`ConversationRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc / findByIdAndTenantIdAndUserId(Long,String,String) → Optional<Conversation>`；`ConversationAccessDeniedException`（web 映射 404，message 中文）——Task 6 消费

- [ ] **Step 1: RepositoryTest 写失败用例**

在 `agent/src/test/java/com/linagent/agent/persistence/RepositoryTest.java`：
1. `@DataJdbcTest` 的 `schema-locations` 追加 `classpath:db/migration/V4__multi_tenant.sql`
2. 追加用例：

```java
@Test
void conversationOwnershipScopesQueries() {
    Conversation a = conversations.save(Conversation.create("A", "conv-x", "default", "linmj", Instant.now()));
    conversations.save(Conversation.create("B", "conv-y", "default", "tester", Instant.now()));

    assertThat(conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc("default", "linmj"))
        .extracting(Conversation::id).containsExactly(a.id());
    assertThat(conversations.findByIdAndTenantIdAndUserId(a.id(), "default", "tester")).isEmpty();
    assertThat(conversations.findByIdAndTenantIdAndUserId(a.id(), "default", "linmj")).hasValue(a);
}
```

既有 `Conversation.create(...)` / `new Conversation(...)` 调用点对位更新（见 Step 3 字段序）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=RepositoryTest`
Expected: 编译失败（create 签名不存在）

- [ ] **Step 3: 实体与仓库**

`Conversation.java` 全量替换：

```java
package com.linagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * 会话实体，对应 conversation 表（V1 DDL + V3 压缩锚点列 + V4 归属列）。
 */
@Table("conversation")
public record Conversation(@Id Long id, String title, String threadId, String compactSummary,
                           Integer compactedTurnSeq, String tenantId, String userId,
                           Instant createdAt, Instant updatedAt) {

    /** 新会话构造：未压缩（无摘要），压缩锚点 0，归属 (tenantId, userId) */
    public static Conversation create(String title, String threadId,
                                      String tenantId, String userId, Instant now) {
        return new Conversation(null, title, threadId, null, 0, tenantId, userId, now, now);
    }
}
```

`ConversationRepository.java` 补：

```java
Optional<Conversation> findByIdAndTenantIdAndUserId(Long id, String tenantId, String userId);
```

（import `java.util.Optional`；`findByTenantIdAndUserIdOrderByUpdatedAtDesc` Task 3 已加。）

`ConversationAccessDeniedException.java`：

```java
package com.linagent.agent.persistence;

/** 会话不存在或不属于当前身份（web 层映射 404，不泄漏存在性） */
public class ConversationAccessDeniedException extends RuntimeException {

    public ConversationAccessDeniedException(Long conversationId) {
        super("会话不存在或无权访问: " + conversationId);
    }
}
```

`CompactionService.java:98-100` 对位替换（仅插入 `conv.tenantId(), conv.userId()`）：

```java
conversations.save(new Conversation(conv.id(), conv.title(), newThreadId, summary,
    maxTurnSeq, conv.tenantId(), conv.userId(), conv.createdAt(), Instant.now()));
```

- [ ] **Step 4: 跑 RepositoryTest 确认通过**

Run: `mvn -pl agent test -Dtest=RepositoryTest`
Expected: 全绿

- [ ] **Step 5: web 404 语义 + 隔离测试**

`web/src/main/java/com/linagent/web/controller/GlobalExceptionHandler.java`：

```java
package com.linagent.web.controller;

import com.linagent.agent.persistence.ConversationAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 领域异常 → HTTP 语义。404 统一文案，不泄漏资源存在性。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConversationAccessDeniedException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> conversationAccessDenied(ConversationAccessDeniedException e) {
        return Map.of("message", e.getMessage());
    }
}
```

`ConversationController.java` 修改四处（方法体首行取 ctx）：

```java
// import 区补：
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.persistence.ConversationAccessDeniedException;
```

```java
@PostMapping
public ConversationResponse create(@RequestBody(required = false) CreateConversationRequest request) {
    AuthContext ctx = AuthContextHolder.require();
    String title = (request == null || request.title() == null || request.title().isBlank())
        ? "新会话" : request.title();
    Conversation first = conversations.save(
        Conversation.create(title, "pending-" + System.nanoTime(), ctx.tenantId(), ctx.userId(), Instant.now()));
    Conversation saved = conversations.save(new Conversation(first.id(), first.title(),
        "conv-" + first.id(), first.compactSummary(), first.compactedTurnSeq(),
        first.tenantId(), first.userId(), first.createdAt(), first.updatedAt()));
    return new ConversationResponse(saved.id(), saved.title(), 0, saved.updatedAt().toString());
}

@GetMapping
public List<ConversationResponse> list() {
    AuthContext ctx = AuthContextHolder.require();
    return conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc(ctx.tenantId(), ctx.userId()).stream()
        .map(c -> new ConversationResponse(c.id(), c.title(),
            turns.countByConversationId(c.id()), c.updatedAt().toString()))
        .toList();
}

@GetMapping("/{id}/turns")
public List<TurnResponse> turns(@PathVariable Long id) {
    AuthContext ctx = AuthContextHolder.require();
    conversations.findByIdAndTenantIdAndUserId(id, ctx.tenantId(), ctx.userId())
        .orElseThrow(() -> new ConversationAccessDeniedException(id));
    return turns.findByConversationIdOrderBySeqAsc(id).stream()
        .map(t -> TurnResponse.from(t, messages.findByTurnIdOrderBySeq(t.id())))
        .toList();
}

@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable Long id) {
    AuthContext ctx = AuthContextHolder.require();
    conversations.findByIdAndTenantIdAndUserId(id, ctx.tenantId(), ctx.userId())
        .orElseThrow(() -> new ConversationAccessDeniedException(id)); // 非属主 404（与 CRUD 一致）
    checkpointCleaner.deleteByConversationId(id);
    conversations.deleteById(id);
}
```

`web/src/test/java/com/linagent/web/controller/ConversationIsolationTest.java`（真实 DB，仿 SkillsSmokeTest 容器骨架；只测 CRUD 不打真实模型）：

```java
package com.linagent.web.controller;

import com.linagent.web.support.TestAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationIsolationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    @Autowired
    WebTestClient webTestClient;

    Long linmjConvId;

    @BeforeEach
    void setup() {
        Map<?, ?> created = webTestClient.mutate().defaultHeaders(TestAuth.LINMJ).build()
            .post().uri("/api/conversations")
            .header("Content-Type", "application/json").bodyValue(Map.of("title", "linmj的会话"))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();
        linmjConvId = ((Number) created.get("id")).longValue();
    }

    @AfterEach
    void cleanup() {
        webTestClient.mutate().defaultHeaders(TestAuth.LINMJ).build()
            .delete().uri("/api/conversations/%d".formatted(linmjConvId))
            .exchange().expectStatus().isNoContent();
    }

    @Test
    void testerCannotSeeLinmjConversations() {
        WebTestClient tester = webTestClient.mutate().defaultHeaders(TestAuth.TESTER).build();

        List<Map<String, Object>> list = tester.get().uri("/api/conversations")
            .exchange().expectStatus().isOk()
            .expectBody(List.class).returnResult().getResponseBody();
        assertThat(list).noneMatch(c -> ((Number) c.get("id")).longValue() == linmjConvId);

        tester.get().uri("/api/conversations/%d/turns".formatted(linmjConvId))
            .exchange().expectStatus().isNotFound();

        tester.delete().uri("/api/conversations/%d".formatted(linmjConvId))
            .exchange().expectStatus().isNotFound();

        // chat 404 断言依赖 Task 6 的 facade 归属预检，本任务先整块注释，Task 6 Step 5 解开：
        // tester.post().uri("/api/conversations/%d/chat".formatted(linmjConvId))
        //     .header("Content-Type", "application/json").bodyValue(Map.of("content", "hi"))
        //     .exchange().expectStatus().isNotFound();
    }
}
```

> 注：chat 走真实 AgentFacade（@SpringBootTest 全上下文），归属预检在 Task 6 之前未落地——**本任务必须保持该块注释**，否则请求会真调模型（消耗配额）。删除后 cleanup 仍以 linmj 身份执行（@AfterEach 幂等：非属主删除本就不生效）。

`ConversationControllerTest.java`：既有打桩 `findAllByOrderByUpdatedAtDesc` 全部替换为 `findByTenantIdAndUserIdOrderByUpdatedAtDesc(any(), any())`；`findById(id)` 打桩替换为 `findByIdAndTenantIdAndUserId(eq(id), any(), any())`；`ArgumentCaptor.forClass(Conversation.class)` 断言新增 `assertThat(captured.tenantId()).isEqualTo("default")` 与 `userId=linmj`。

- [ ] **Step 6: 跑 web 全量确认通过**

Run: `mvn -pl web test`
Expected: 全绿（隔离测试：tester 列表无 linmj 会话、turns/delete 404）

- [ ] **Step 7: Commit**

```bash
git add agent/src web/src
git commit -m "feat: conversation 归属隔离——实体/派生查询/控制器 ctx 接线/404 统一语义（不泄漏存在性）"
```

---

### Task 5: WorkspaceResolver（个人根 + shared 挂载）

**Files:**
- Create: `agent/src/main/java/com/linagent/agent/workspace/WorkspaceResolver.java`
- Test: `agent/src/test/java/com/linagent/agent/workspace/WorkspaceResolverTest.java`

**Interfaces:**
- Consumes: `ProjectPathResolver.resolveDir`（已有）
- Produces: `WorkspaceResolver.personalRoot(String tenantId, String userId) → Path`（幂等 provision：建 shared、建个人根、建 `shared` 相对符号链接）；`sharedRoot(String tenantId) → Path`；非法身份段抛 IllegalArgumentException——Task 6 消费

- [ ] **Step 1: 写失败测试**

```java
package com.linagent.agent.workspace;

import com.linagent.agent.tools.FileTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceResolverTest {

    @TempDir
    Path tmp;

    private WorkspaceResolver resolver() {
        return new WorkspaceResolver(tmp.toString()); // 绝对路径直通（ProjectPathResolver 语义）
    }

    @Test
    void provisionsPersonalRootWithSharedSymlink() throws IOException {
        Path personal = resolver().personalRoot("tenant-a", "linmj");
        assertThat(personal).isEqualTo(tmp.resolve("tenant-a").resolve("users").resolve("linmj"));
        assertThat(Files.isDirectory(personal)).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("tenant-a").resolve("shared"))).isTrue();
        assertThat(Files.isSymbolicLink(personal.resolve("shared"))).isTrue();
    }

    @Test
    void provisioningIsIdempotent() {
        WorkspaceResolver r = resolver();
        Path first = r.personalRoot("tenant-a", "linmj");
        Path second = r.personalRoot("tenant-a", "linmj");
        assertThat(second).isEqualTo(first); // 二次不抛、同路径
    }

    @Test
    void sharedVisibleFromPersonalRootButSiblingsSealed() throws IOException {
        WorkspaceResolver r = resolver();
        Path personal = r.personalRoot("tenant-a", "linmj");
        r.personalRoot("tenant-a", "zhangsan"); // 兄弟用户
        Files.writeString(tmp.resolve("tenant-a").resolve("shared").resolve("规范.md"), "团队规范");

        FileTools tools = new FileTools(personal);
        assertThat(tools.read_file("shared/规范.md")).isEqualTo("团队规范"); // shared 可达
        assertThatThrownBy(() -> tools.read_file("../zhangsan/x")) // 兄弟越界
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void illegalIdentitySegmentRejected() {
        WorkspaceResolver r = resolver();
        assertThatThrownBy(() -> r.personalRoot("../etc", "linmj"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.personalRoot("tenant-a", "a/b"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=WorkspaceResolverTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```java
package com.linagent.agent.workspace;

import com.linagent.agent.config.ProjectPathResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 租户/个人两级工作区解析（spec §6）：
 * {基根}/{tenant}/shared 为租户共享真实目录；{基根}/{tenant}/users/{user} 为个人根（工具沙箱），
 * 根内 shared 符号链接（相对路径 ../../shared，基根整体搬迁不断链）挂载租户共享区。
 * FileTools.resolveSafely 的 normalize 纯词法不解析链接：链接在根内通过校验、
 * ../兄弟目录 越界拒绝——隔离由此天然成立。
 * 幂等：目录/链接存在即跳过。身份段白名单校验防路径穿越（纵深防御，虽经 app_user 校验）。
 */
@Component
public class WorkspaceResolver {

    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Path baseRoot;

    public WorkspaceResolver(@Value("${agent.workspace-root:./workspace}") String workspaceRoot) {
        this.baseRoot = ProjectPathResolver.resolveDir(workspaceRoot);
    }

    public Path baseRoot() {
        return baseRoot;
    }

    public Path sharedRoot(String tenantId) {
        return baseRoot.resolve(requireSegment(tenantId)).resolve("shared");
    }

    /** 个人根（含幂等 provision：shared 目录、个人目录、shared 相对符号链接） */
    public Path personalRoot(String tenantId, String userId) {
        Path tenantDir = baseRoot.resolve(requireSegment(tenantId));
        Path shared = tenantDir.resolve("shared");
        Path personal = tenantDir.resolve("users").resolve(requireSegment(userId));
        try {
            Files.createDirectories(shared);
            Files.createDirectories(personal);
            Path link = personal.resolve("shared");
            if (!Files.exists(link)) {
                Files.createSymbolicLink(link, Path.of("..", "..", "shared"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("工作区初始化失败: " + personal, e);
        }
        return personal;
    }

    private String requireSegment(String value) {
        if (value == null || !SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("非法身份段（仅字母数字_-，1-64 字符）: " + value);
        }
        return value;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl agent test -Dtest=WorkspaceResolverTest`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: agent 全量 + Commit**

Run: `mvn -pl agent test` → 全绿后：

```bash
git add agent/src/main/java/com/linagent/agent/workspace/ agent/src/test/java/com/linagent/agent/workspace/
git commit -m "feat: WorkspaceResolver——个人根+shared 相对符号链接挂载，身份段白名单防穿越"
```

---

### Task 6: AgentFacade defer 外捕获 + AgentFactory 每轮工具实例

**Files:**
- Modify: `agent/src/main/java/com/linagent/agent/agent/AgentFactory.java`（构造去 FileTools/workspaceRoot，加 WorkspaceResolver；create 带 ctx）
- Modify: `agent/src/main/java/com/linagent/agent/config/AgentBeansConfig.java`（删 fileTools Bean）
- Modify: `agent/src/main/java/com/linagent/agent/facade/AgentFacade.java:64-65`（defer 外 require + 归属预检；create 传 ctx）
- Modify: `agent/src/test/java/com/linagent/agent/agent/AgentFactoryTest.java`（构造参数适配 + 每轮工作区断言）
- Modify: `agent/src/test/java/com/linagent/agent/facade/AgentFacadeTest.java`（@BeforeEach set Holder + 归属打桩）
- Modify: `web/src/test/java/com/linagent/web/controller/ConversationIsolationTest.java`（解开 chat 404 断言）

**Interfaces:**
- Consumes: Task 2 `AuthContext/AuthContextHolder`；Task 4 `findByIdAndTenantIdAndUserId` + `ConversationAccessDeniedException`；Task 5 `WorkspaceResolver.personalRoot`
- Produces: `AgentFactory.create(AuthContext ctx, Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture[, List<Interceptor>]) → AgentHandle`；工具实例每轮构造、烤入个人根——对外行为不变

- [ ] **Step 1: 适配 AgentFactoryTest 写期望（先红）**

`AgentFactoryTest.java`：构造段替换（`new AgentFactory(chatModel, thinkingExtractor, fileTools, ...)` → 去 fileTools/workspaceRoot 参数、加 `new WorkspaceResolver(workspaceTmp.toString())` 的 @TempDir），并加断言"两轮 create 使用各自身份的个人根"（通过 AgentHandle 无从直接断言路径——以行为断言：linmj 与 tester 各 create 一轮，检查两个个人根目录被 provision 出来）：

```java
// 追加用例（workspaceTmp 为本测试 @TempDir）：
@Test
void createProvisionsPersonalWorkspacePerIdentity() {
    WorkspaceResolver resolver = new WorkspaceResolver(workspaceTmp.toString());
    // ...按本测试既有 mock 依赖构造 factory（chatModel/thinkingExtractor/registry/promptBuilder/saver 同既有构造段）...
    factory.create(new AuthContext("t", "linmj", "n"), Sinks.many().unicast().onBackpressureBuffer(),
        new AtomicReference<>());
    factory.create(new AuthContext("t", "tester", "n"), Sinks.many().unicast().onBackpressureBuffer(),
        new AtomicReference<>());
    assertThat(Files.isDirectory(workspaceTmp.resolve("t/users/linmj"))).isTrue();
    assertThat(Files.isDirectory(workspaceTmp.resolve("t/users/tester"))).isTrue();
}
```

（`AuthContext` import 自 `com.linagent.agent.context`；既有用例的 create 调用全部补第一参 `TestAuth 语境的 new AuthContext("default","linmj","林同学")`——agent 模块无 TestAuth，用本文件常量 `CTX`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl agent test -Dtest=AgentFactoryTest`
Expected: 编译失败（create 签名不存在）

- [ ] **Step 3: 改 AgentFactory + AgentBeansConfig**

`AgentFactory.java`：
1. import 区：去 `com.linagent.agent.tools.FileTools` 依赖字段，加 `com.linagent.agent.context.AuthContext`、`com.linagent.agent.workspace.WorkspaceResolver`、`java.nio.file.Path`
2. 字段/构造：删 `private final FileTools fileTools;` 与 `private final String workspaceRoot;`，构造参数 `FileTools fileTools` 与 `@Value workspaceRoot` 换成 `WorkspaceResolver workspaceResolver`
3. `create` 两个重载首参加 `AuthContext ctx`；私有 create 方法体开头：

```java
Path personalRoot = workspaceResolver.personalRoot(ctx.tenantId(), ctx.userId());
FileTools fileTools = new FileTools(personalRoot);
```

4. `groupedTools` 行：`new CsvSummaryTool(fileTools.workspace()).callback()` 不变（workspace() 即个人根）
5. `.shellTool2(ShellTool2.builder(workspaceRoot).build())` → `.shellTool2(ShellTool2.builder(personalRoot.toString()).build())`

`AgentBeansConfig.java`：整段删除 `fileTools` Bean（该文件 `FileTools`/`Path` import 若仅它使用则一并清理）。

- [ ] **Step 4: 改 AgentFacade（defer 外捕获红线）**

`AgentFacade.java` chat 方法（64-65 行起）改为：

```java
public Flux<AgentEvent> chat(Long conversationId, String content) {
    // ThreadLocal 红线（spec §5）：defer 外捕获——订阅期在 reactor 线程执行，
    // 届时 Filter finally 已清理且线程不同，defer 内读 Holder 必炸。
    AuthContext ctx = AuthContextHolder.require();
    // 归属预检同步抛出：HTTP 404（GlobalExceptionHandler 映射）先于 SSE 建流
    conversations.findByIdAndTenantIdAndUserId(conversationId, ctx.tenantId(), ctx.userId())
        .orElseThrow(() -> new ConversationAccessDeniedException(conversationId));
    return Flux.defer(() -> {
```

（defer lambda 体内 `agentFactory.create(sideEvents, usageCapture, toolInterceptor)` 改为 `agentFactory.create(ctx, sideEvents, usageCapture, toolInterceptor)`；import 补 `AuthContext/AuthContextHolder/ConversationAccessDeniedException`。）

`AgentFacadeTest.java`：
1. `@BeforeEach` 末尾加 `AuthContextHolder.set(new AuthContext("default", "linmj", "林同学"));`，新增 `@AfterEach void clean() { AuthContextHolder.clear(); }`
2. `conversations` mock 补：

```java
when(conversations.findByIdAndTenantIdAndUserId(any(), eq("default"), eq("linmj")))
    .thenReturn(Optional.of(conv));
```

（`conv` 为该测试既有会话桩对象，找不到全局 conv 时在用到的方法内构造：`new Conversation(1L, "t", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now())`。）

- [ ] **Step 5: 解开 ConversationIsolationTest 的 chat 404 断言并跑全量**

`ConversationIsolationTest.java`：恢复被注释的 `tester.post().uri("/api/conversations/%d/chat"...)` 断言块（`expectStatus().isNotFound()`——AgentFacade 同步预检抛 ConversationAccessDeniedException，GlobalExceptionHandler 映射 404；请求不会触达模型）。

Run: `mvn -pl agent install -DskipTests && mvn test`
Expected: agent + web 全绿

- [ ] **Step 6: 手工实证（本地起服务，双身份 curl）**

```bash
mvn -pl agent install -DskipTests -q && (mvn -pl web spring-boot:run > /tmp/linagent-backend.log 2>&1 &)
# 等 Started WebApplication 后：
# 1) 无身份 401
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/conversations            # 期望 401
# 2) linmj 建会话
curl -s -X POST http://localhost:8080/api/conversations -H "Content-Type: application/json" \
  -H "x-tenant-id: default" -H "x-user-id: linmj" -d '{"title":"隔离验证"}'
# 3) tester 看不到（列表无此 id）、chat 404（换成上一步返回的 id）
curl -s http://localhost:8080/api/conversations -H "x-tenant-id: default" -H "x-user-id: tester"
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/conversations/{id}/chat \
  -H "Content-Type: application/json" -H "x-tenant-id: default" -H "x-user-id: tester" \
  -d '{"content":"hi"}'                                                                      # 期望 404
# 4) 工作区落位
ls workspace/default/shared workspace/default/users/linmj                                       # 目录 + shared 链接
# 5) 让 linmj 写文件后（正常聊天"把 hello 写入 note.md"），验证 tester 读不到：
#    tester 会话内让它 read_file note.md → 失败（个人隔离生效）
```

- [ ] **Step 7: Commit**

```bash
git add agent/src web/src
git commit -m "feat: 工具实例每轮构造烤入个人工作区 + facade defer 外捕获身份（ThreadLocal 红线）+ chat 归属 404"
```

---

### Task 7: 全量回归 + 文档收尾

**Files:**
- Modify: `CLAUDE.md`（架构段工作区语义、坑清单、常用命令 curl 示例）
- Modify: `web/src/test/java/com/linagent/web/smoke/SkillsSmokeTest.java` 顶部注释（manual 冒烟补身份 header 说明，代码 Task 3 已适配）

**Interfaces:**
- Consumes: 前 6 个任务的全部产物
- Produces: 文档与回归结论（无代码接口）

- [ ] **Step 1: 全量测试**

Run: `mvn test && cd frontend && npx vitest run`（后者在 Task 8 前基线先跑一次确认现状绿）
Expected: 全绿

- [ ] **Step 2: CLAUDE.md 更新**

在「实证过的坑」清单追加一行：

```
- @WebMvcTest 切片会自动装配 Filter 类型 Bean：AuthContextFilter 落地后所有 @WebMvcTest 必须 @MockBean RequestAuthenticator 并打桩（返回 TestAuth.LINMJ_CTX），否则 401/上下文失败。
```

在「架构」段的 `agent/` 描述后补一段：

```
### 身份与工作区（v0.2）

请求带 `x-tenant-id`/`x-user-id`（app_user 表校验，未知 401）。AuthContextFilter →
AuthContextHolder（ThreadLocal，仅入口有效）→ facade.chat() **defer 外**捕获（订阅期在
reactor 线程，届时已清理——defer 内读取是已实证的坑）。工具每轮构造，个人根 =
`{agent.workspace-root}/{tenant}/users/{user}`，根内 `shared` 符号链接挂载租户共享区
（WorkspaceResolver 幂等 provision）。conversation 按 (tenant_id, user_id) 隔离，非属主 404。
前端身份走 VITE_TENANT_ID/VITE_USER_ID（缺省 default/linmj）。
```

「常用命令」块补：

```bash
# 双身份手工验证（curl 示例）
curl -H "x-tenant-id: default" -H "x-user-id: linmj" http://localhost:8080/api/conversations
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md web/src/test/java/com/linagent/web/smoke/SkillsSmokeTest.java
git commit -m "docs: CLAUDE.md 补身份/工作区架构与 @WebMvcTest Filter 坑"
```

---

### Task 8: 前端——ApiError + 身份 header + 404/401 无感兼容

**Files:**
- Create: `frontend/src/api/identity.ts`
- Create: `frontend/src/api/error.ts`
- Modify: `frontend/src/api/rest.ts`
- Modify: `frontend/src/api/sse.ts`
- Modify: `frontend/src/App.vue`
- Create: `frontend/.env.example`（若无则建）
- Test: `frontend/src/api/__tests__/error.spec.ts`

**Interfaces:**
- Consumes: Task 3/4/6 的后端语义（401 JSON `{message}`、404 JSON `{message}`、CRUD 隔离）
- Produces: `ApiError{status, message, unauthorized, notFound}`；`identityHeaders() → Record<string,string>`——App.vue 消费

- [ ] **Step 1: 写失败测试**

`frontend/src/api/__tests__/error.spec.ts`：

```ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../error'
import { listConversations } from '../rest'
import { identityHeaders } from '../identity'

describe('ApiError 分类与身份 header', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('非 2xx 解析 JSON message 为 ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ message: '会话不存在或无权访问: 9' }), { status: 404 })))
    await expect(listConversations()).rejects.toMatchObject({
      status: 404, message: '会话不存在或无权访问: 9', notFound: true,
    })
  })

  it('非 JSON 错误体回退为 HTTP 状态描述', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 401 })))
    await expect(listConversations()).rejects.toBeInstanceOf(ApiError)
  })

  it('所有请求携带身份 header', async () => {
    const fetchMock = vi.fn(async () => new Response('[]'))
    vi.stubGlobal('fetch', fetchMock)
    await listConversations()
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['x-tenant-id']).toBe('default')
    expect((init.headers as Record<string, string>)['x-user-id']).toBe('linmj')
    expect(identityHeaders()).toEqual({ 'x-tenant-id': 'default', 'x-user-id': 'linmj' })
  })
})
```

Run: `cd frontend && npx vitest run src/api/__tests__/error.spec.ts`
Expected: FAIL（error.ts/identity.ts 不存在）

- [ ] **Step 2: 实现 error.ts / identity.ts / rest.ts / sse.ts**

`frontend/src/api/error.ts`：

```ts
/** 类型化 API 错误：替代裸 "HTTP 404"，404/401 分支由此无感处理（spec §7.2） */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }

  get unauthorized(): boolean {
    return this.status === 401
  }

  get notFound(): boolean {
    return this.status === 404
  }

  /** 从 fetch Response 构造：优先取后端 JSON {message}，回退 HTTP 状态描述 */
  static async from(resp: Response): Promise<ApiError> {
    let message = `HTTP ${resp.status}`
    try {
      const body = (await resp.json()) as { message?: string }
      if (body?.message) message = body.message
    } catch {
      /* 非 JSON 体保持回退 */
    }
    return new ApiError(resp.status, message)
  }
}
```

`frontend/src/api/identity.ts`：

```ts
/** 前端身份（env 可覆盖；本期无登录，401 时提示检查配置） */
export const TENANT_ID = (import.meta.env.VITE_TENANT_ID as string | undefined) ?? 'default'
export const USER_ID = (import.meta.env.VITE_USER_ID as string | undefined) ?? 'linmj'

export const identityHeaders = (): Record<string, string> => ({
  'x-tenant-id': TENANT_ID,
  'x-user-id': USER_ID,
})
```

`frontend/src/api/rest.ts` 全量替换：

```ts
import { ApiError } from './error'
import { identityHeaders } from './identity'

const json = async (url: string, init?: RequestInit) => {
  const resp = await fetch(url, { ...init, headers: { ...identityHeaders(), ...(init?.headers ?? {}) } })
  if (!resp.ok) throw await ApiError.from(resp)
  return resp.json()
}

export const listConversations = () => json('/api/conversations')
export const createConversation = (title?: string) =>
  json('/api/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
export const getTurns = (id: number) => json(`/api/conversations/${id}/turns`)
export const deleteConversation = async (id: number) => {
  const resp = await fetch(`/api/conversations/${id}`, {
    method: 'DELETE',
    headers: identityHeaders(),
  })
  if (!resp.ok && resp.status !== 404) throw await ApiError.from(resp) // 404 视为已删（幂等）
}
```

`frontend/src/api/sse.ts` 的 `streamSse`：fetch 调用补 header 合并、错误分支改 ApiError：

```ts
import { ApiError } from './error'
import { identityHeaders } from './identity'
// ...
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...identityHeaders() },
    body: JSON.stringify(body),
  })
  if (!resp.ok || !resp.body) {
    throw await ApiError.from(resp)
  }
```

`frontend/.env.example` 追加：

```
# 身份（须为后端 app_user 表已有用户；默认 default/linmj、default/tester）
VITE_TENANT_ID=default
VITE_USER_ID=linmj
```

- [ ] **Step 3: 跑 Step 1 测试确认通过**

Run: `cd frontend && npx vitest run src/api/__tests__/error.spec.ts`
Expected: 3 个用例全绿

- [ ] **Step 4: App.vue 404/401 无感处理**

script setup 区（`const sending = ref(false)` 附近）加：

```ts
import { ApiError } from './api/error'
import { TENANT_ID, USER_ID } from './api/identity'

const globalError = ref('')

// 401：身份无效——指向 env 配置（无登录可跳，唯一出路）
const authErrorMessage = () =>
  `当前身份（${TENANT_ID}/${USER_ID}）未注册或已失效，请检查 VITE_TENANT_ID / VITE_USER_ID 配置`

// 会话不可用：从列表移除并回到欢迎态（activeId 置空）
const dropConversation = (id: number) => {
  conversations.value = conversations.value.filter((c) => c.id !== id)
  if (activeId.value === id) {
    activeId.value = null
    turns.value = []
  }
}
```

`select`（原 33-40 行）包错误分支：

```ts
const select = async (id: number) => {
  activeId.value = id
  sidebarOpen.value = false
  turns.value = []
  try {
    const history: TurnRecord[] = await getTurns(id)
    turns.value = history.map(turnFromRecord)
    await scrollToBottom(true)
  } catch (err) {
    if (err instanceof ApiError && err.notFound) {
      globalError.value = '该会话不存在或无权访问，已自动移除'
      dropConversation(id)
    } else if (err instanceof ApiError && err.unauthorized) {
      globalError.value = authErrorMessage()
    } else {
      throw err
    }
  }
}
```

`send` 的 catch（原 `failTurn(turn, err)` 处）改为：

```ts
  } catch (err) {
    if (err instanceof ApiError && err.notFound) {
      failTurn(turn, new Error('该会话已不可用（可能已删除或归属其他用户）'))
      dropConversation(activeId.value!)
      await refresh()
    } else if (err instanceof ApiError && err.unauthorized) {
      globalError.value = authErrorMessage()
      failTurn(turn, err)
    } else {
      failTurn(turn, err)
    }
  } finally {
```

`refresh` 补 401 兜底：

```ts
const refresh = async () => {
  try {
    conversations.value = await listConversations()
  } catch (err) {
    if (err instanceof ApiError && err.unauthorized) globalError.value = authErrorMessage()
    else throw err
  }
}
```

template：聊天主区顶部（`<textarea>` 所在面板上方）加横幅：

```html
<div class="global-error" v-if="globalError" @click="globalError = ''">
  {{ globalError }}（点击关闭）
</div>
```

style 区（`.error-line` 附近风格）加：

```css
.global-error {
  margin: 0 16px 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(192, 57, 43, 0.08);
  color: #b03a2e;
  font-size: 13px;
  cursor: pointer;
}
```

- [ ] **Step 5: 前端全量测试**

Run: `cd frontend && npx vitest run`
Expected: 全绿（既有 turn.spec/sse.spec 无回归）

- [ ] **Step 6: 端到端手工验证（后端已带全部改动在跑）**

```bash
# 1) 前端默认身份可用：cd frontend && npm run dev，刷新页面，会话列表正常
# 2) 404 无感：后端 curl 以 tester 身份删掉前端正打开的会话 → 前端再发消息，
#    turn 显示"该会话已不可用…"且列表自动刷新移除（不出现裸 404）
# 3) 401 提示：frontend/.env.local 写 VITE_USER_ID=nobody → 刷新 → 全局横幅提示检查配置
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src frontend/.env.example
git commit -m "feat(web): ApiError 分类 + 身份 header + 404/401 无感兼容（会话自动移除/配置指引横幅）"
```

---

## 验收清单（对照 spec）

- [ ] 无 header / 未知身份 → 401 JSON（Task 3）
- [ ] linmj/tester 会话互不可见，chat/turns/delete 非属主 404（Task 4/6）
- [ ] `workspace/{tenant}/shared` + `users/{user}/shared` 链接落位，兄弟越界拒绝（Task 5/6）
- [ ] ThreadLocal：请求后无残留、二次无 header 401、defer 外捕获（Task 2/3/6）
- [ ] 前端 404/401 无感（Task 8）
- [ ] `mvn test` + `npx vitest run` 全绿（Task 7/8）
