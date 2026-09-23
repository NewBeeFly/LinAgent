# javaAgent ReAct Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建基于 Spring AI Alibaba 1.1.2.3 ReactAgent 的 ReAct Agent 服务：StepFun 模型、常驻/渐进式技能、bash+文件工具、PG 按轮次结构化存储、SSE 流式接口、Vue3 前端。

**Architecture:** Maven 多模块（`agent` 引擎库 + `web` 可启动接口层）+ `frontend` Vue3 独立目录。agent 模块产出框架无关的 `Flux<AgentEvent>`，web 模块转 SSE。记忆走 SAA checkpoint saver（threadId 关联 conversation），展示走 turn/message 结构化表。每轮构建独立 ReactAgent 实例（便于注入该轮的事件通道：thinking 旁路 + 工具事件拦截器）。

**Tech Stack:** JDK 21, Spring Boot 3.5.3, spring-ai-alibaba-agent-framework 1.1.2.3, spring-ai-openai 1.1.2, PostgreSQL 16+, Flyway, Spring Data JDBC, Vue3+Vite+TS。

**Spec:** `docs/superpowers/specs/2026-09-20-react-agent-design.md`（执行者需同时阅读 spec 与本计划）

## Global Constraints

- JDK 21；Spring Boot 3.5.3；SAA BOM 1.1.2.3；Spring AI BOM 1.1.2——版本写死，不追新
- 包名：agent 模块 `com.javaagent.agent.*`，web 模块 `com.javaagent.web.*`
- 模型：StepFun `step-3.7-flash`，base-url `https://api.stepfun.com/step_plan`（Spring AI 默认拼 `/v1/chat/completions`，与实测一致）；api-key 走环境变量 `STEPFUN_API_KEY` 或 `application-local.yml`（不入 git）
- PG：`jdbc:postgresql://localhost:5432/javaagent`，账号 `jiege` / `<PG_PASSWORD>`（本地 `application-local.yml`，不入 git）
- 大文本（system prompt 模板）文件化到 `resources/prompts/`，启动加载缓存；代码不写长字符串
- 技能目录：仓库根 `./skills`；常驻技能 frontmatter `resident: true`
- 存储：不存 delta，存完整消息（THINKING/TEXT 按段落，TOOL_CALL/TOOL_RESULT 各一行，`call_id` 关联）
- 每任务 TDD：失败测试 → 实现 → 通过 → commit；框架 JUnit5 + Mockito + AssertJ + Testcontainers(PG)
- SAA API 签名以 1.1.2.3 实际 jar 为准：编译失败时 `mvn dependency:sources -DincludeArtifactIds=spring-ai-alibaba-agent-framework` 后对照源码微调（微调签名不算偏离计划，计划代码基于官方文档 API）
- 一个任务一个 commit，conventional commits 风格

---

### Task 1: 工程骨架（git + Maven 多模块 + 基础配置）

**Files:**
- Create: `.gitignore`, `pom.xml`, `agent/pom.xml`, `web/pom.xml`
- Create: `web/src/main/java/com/javaagent/web/WebApplication.java`
- Create: `web/src/main/resources/application.yml`, `web/src/main/resources/application-local.yml`
- Create: `agent/src/main/resources/prompts/system-prompt.md`
- Create: `agent/src/test/java/com/javaagent/agent/PlaceholderTest.java`

**Interfaces:**
- Produces: 可编译的多模块工程；`WebApplication`（唯一启动类，扫描 `com.javaagent`）；配置键 `agent.skills-root`、`agent.workspace-root`、`agent.compaction.threshold-tokens`

- [ ] **Step 1: git init 与 .gitignore**

```bash
cd /Users/newbeefly/Coder/Project/Claude/javaAgent
git init -b main
cat > .gitignore << 'EOF'
target/
node_modules/
dist/
.DS_Store
*.iml
.idea/
application-local.yml
openai_key.md
openai_key_副本.md
frontend/.env.local
EOF
git add .gitignore docs/
git commit -m "chore: git init 与设计文档入库"
```

- [ ] **Step 2: 父 POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.3</version>
    <relativePath/>
  </parent>
  <groupId>com.javaagent</groupId>
  <artifactId>java-agent-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <modules>
    <module>agent</module>
    <module>web</module>
  </modules>
  <properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.2</spring-ai.version>
    <saa.version>1.1.2.3</saa.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-bom</artifactId>
        <version>${saa.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>1.20.6</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

- [ ] **Step 3: agent 模块 POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.javaagent</groupId>
    <artifactId>java-agent-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>agent</artifactId>
  <dependencies>
    <dependency>
      <groupId>com.alibaba.cloud.ai</groupId>
      <artifactId>spring-ai-alibaba-agent-framework</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 4: web 模块 POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.javaagent</groupId>
    <artifactId>java-agent-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>web</artifactId>
  <dependencies>
    <dependency>
      <groupId>com.javaagent</groupId>
      <artifactId>agent</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 5: 启动类与配置文件**

`web/src/main/java/com/javaagent/web/WebApplication.java`：

```java
package com.javaagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.javaagent")
public class WebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
```

`web/src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: java-agent
  profiles:
    active: local
  ai:
    openai:
      api-key: ${STEPFUN_API_KEY:}
      base-url: https://api.stepfun.com/step_plan
      chat:
        options:
          model: step-3.7-flash
  flyway:
    enabled: true
agent:
  skills-root: ./skills
  workspace-root: ./workspace
  compaction:
    threshold-tokens: 24000
```

`web/src/main/resources/application-local.yml`（git 忽略）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/javaagent
    username: jiege
    password: <PG_PASSWORD>
  ai:
    openai:
      api-key: 1idjGLL3nmeJzncWgFTAylqqOSoo188SR6O7HFImSlquH0OCvXI3oYQi5cdJpKFKH
```

`agent/src/main/resources/prompts/system-prompt.md`：

```markdown
你是 javaAgent，一个运行在 JDK21 工程环境里的编程助手。

{resident_skills}

## 工具使用约定
- 文件操作使用 read_file / write_file / list_dir，路径为相对工作区根的路径
- 需要执行命令时使用 shell 工具
- 工具失败时阅读错误信息，调整参数后重试或换方案
```

- [ ] **Step 6: 占位测试验证测试链路**

`agent/src/test/java/com/javaagent/agent/PlaceholderTest.java`：

```java
package com.javaagent.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderTest {
    @Test
    void skeletonCompiles() {
        assertThat(true).isTrue();
    }
}
```

- [ ] **Step 7: 编译验证**

```bash
mvn -q -DskipTests compile && mvn -q -pl agent test
```
Expected: BUILD SUCCESS（无编译错误）

- [ ] **Step 8: Commit**

```bash
git add pom.xml agent/ web/
git commit -m "chore: Maven 多模块工程骨架（agent 引擎 + web 接口层）"
```

---

### Task 2: PG 建库 + Flyway 表结构

**Files:**
- Create: `web/src/main/resources/db/migration/V1__init.sql`
- Test: `web/src/test/java/com/javaagent/web/migration/FlywayMigrationTest.java`

**Interfaces:**
- Produces: 表 `conversation(id, title, thread_id, created_at, updated_at)`、`turn(id, conversation_id, seq, status, finish_reason, usage, started_at, finished_at)`、`message(id, turn_id, seq, msg_type, content, call_id, tool_name, arguments, result, success, duration_ms, created_at)`；msg_type CHECK 含 `USER/THINKING/TEXT/TOOL_CALL/TOOL_RESULT/ERROR/SUMMARY`

- [ ] **Step 1: 建库（本地执行一次）**

```bash
psql -h localhost -U jiege -d postgres -c "CREATE DATABASE javaagent OWNER jiege;" || echo "已存在则忽略"
```

- [ ] **Step 2: 写失败测试（Testcontainers 验证迁移可执行）**

`web/src/test/java/com/javaagent/web/migration/FlywayMigrationTest.java`：

```java
package com.javaagent.web.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@Testcontainers
class FlywayMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesThreeTables() {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name in ('conversation','turn','message')",
            Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void messageTypeCheckContainsAllTypes() {
        String types = jdbcTemplate.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = 'message_msg_type_check'",
            String.class);
        assertThat(types).contains("USER", "THINKING", "TEXT", "TOOL_CALL", "TOOL_RESULT", "ERROR", "SUMMARY");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
mvn -q -pl web test -Dtest=FlywayMigrationTest
```
Expected: FAIL（表不存在 / V1 迁移缺失）

- [ ] **Step 4: V1 迁移脚本**

`web/src/main/resources/db/migration/V1__init.sql`：

```sql
CREATE TABLE conversation (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200),
    thread_id   VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE turn (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    seq             INT         NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    finish_reason   VARCHAR(50),
    usage           JSONB,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    UNIQUE (conversation_id, seq)
);

CREATE TABLE message (
    id          BIGSERIAL PRIMARY KEY,
    turn_id     BIGINT       NOT NULL REFERENCES turn(id) ON DELETE CASCADE,
    seq         INT          NOT NULL,
    msg_type    VARCHAR(20)  NOT NULL CHECK (msg_type IN
        ('USER','THINKING','TEXT','TOOL_CALL','TOOL_RESULT','ERROR','SUMMARY')),
    content     TEXT,
    call_id     VARCHAR(100),
    tool_name   VARCHAR(100),
    arguments   JSONB,
    result      TEXT,
    success     BOOLEAN,
    duration_ms BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (turn_id, seq)
);

CREATE INDEX idx_turn_conversation ON turn(conversation_id, seq);
CREATE INDEX idx_message_turn ON message(turn_id, seq);
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -q -pl web test -Dtest=FlywayMigrationTest
```
Expected: PASS（两个测试均绿）

- [ ] **Step 6: Commit**

```bash
git add web/src
git commit -m "feat: Flyway V1 会话/轮次/消息表结构"
```

---

### Task 3: thinking 流式链路验证（技术风险前置）+ ThinkingExtractor

**Files:**
- Create: `agent/src/test/java/com/javaagent/agent/stream/StepFunStreamingProbeTest.java`（manual tag）
- Create: `agent/src/main/java/com/javaagent/agent/stream/ThinkingExtractor.java`
- Create: `agent/src/main/java/com/javaagent/agent/stream/OpenAiThinkingExtractor.java`
- Test: `agent/src/test/java/com/javaagent/agent/stream/OpenAiThinkingExtractorTest.java`

**Interfaces:**
- Consumes: Spring AI 自动装配的 `OpenAiChatModel`（spring-ai-starter-model-openai）
- Produces: `ThinkingExtractor` 接口：

```java
public interface ThinkingExtractor {
    Optional<String> thinkingDelta(ChatResponse response);
    Optional<String> textDelta(ChatResponse response);
}
```

- [ ] **Step 1: 探针测试（手动执行，记录发现）**

`agent/src/test/java/com/javaagent/agent/stream/StepFunStreamingProbeTest.java`：

```java
package com.javaagent.agent.stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

/**
 * 探针：确认 StepFun 流式响应中 reasoning_content 在 Spring AI ChatResponse 里的落点。
 * 结论记录到本类的 CONCLUSION 常量（ThinkingExtractor 实现依据）。
 */
@Tag("manual")
@SpringBootTest
class StepFunStreamingProbeTest {

    /** 探针结论占位：运行后回填，例如 "thinking 落在 result.output.metadata.reasoningContent" */
    static final String CONCLUSION = "待运行回填";

    @Autowired
    org.springframework.ai.chat.model.ChatModel chatModel;

    @Test
    void probeStreamingShape() {
        Flux<ChatResponse> flux = chatModel.stream(
            new Prompt("1+1等于几？先思考再回答"));
        flux.doOnNext(r -> {
            System.out.println("=== chunk ===");
            System.out.println("text=" + r.getResult().getOutput().getText());
            System.out.println("outputMetadata=" + r.getResult().getOutput().getMetadata());
            System.out.println("responseMetadata=" + r.getMetadata());
        }).blockLast();
    }
}
```

- [ ] **Step 2: 运行探针并记录结论**

```bash
cd /Users/newbeefly/Coder/Project/Claude/javaAgent
mvn -q -pl agent test -Dtest=StepFunStreamingProbeTest -Dgroups=manual \
  -Dspring.profiles.active=local -Dspring.datasource.url=jdbc:postgresql://localhost:5432/javaagent \
  -Dspring.datasource.username=jiege -Dspring.datasource.password=<PG_PASSWORD>
```
Expected: 控制台输出各 chunk 的 text/metadata 形态。**人工判断** reasoning_content 落点（候选：output metadata 的 `reasoningContent` 键、或独立字段），把结论回填到 `CONCLUSION` 常量。若 text 与 thinking 无法区分（都为 null 或合并），则确认需要走兜底：见 Step 4 的 metadata 路径优先级。

- [ ] **Step 3: 写失败测试**

`agent/src/test/java/com/javaagent/agent/stream/OpenAiThinkingExtractorTest.java`：

```java
package com.javaagent.agent.stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiThinkingExtractorTest {

    private final OpenAiThinkingExtractor extractor = new OpenAiThinkingExtractor();

    private ChatResponse response(String text, Map<String, Object> outputMetadata) {
        AssistantMessage msg = new AssistantMessage(text, outputMetadata);
        return new ChatResponse(java.util.List.of(new Generation(msg)));
    }

    @Test
    void extractsThinkingFromOutputMetadata() {
        ChatResponse r = response(null, Map.of("reasoningContent", "思考中"));
        assertThat(extractor.thinkingDelta(r)).contains("思考中");
        assertThat(extractor.textDelta(r)).isEmpty();
    }

    @Test
    void extractsTextWhenPresent() {
        ChatResponse r = response("正文内容", Map.of());
        assertThat(extractor.textDelta(r)).contains("正文内容");
        assertThat(extractor.thinkingDelta(r)).isEmpty();
    }

    @Test
    void emptyResponseYieldsNothing() {
        ChatResponse r = response(null, Map.of());
        assertThat(extractor.thinkingDelta(r)).isEmpty();
        assertThat(extractor.textDelta(r)).isEmpty();
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

```bash
mvn -q -pl agent test -Dtest=OpenAiThinkingExtractorTest
```
Expected: FAIL（OpenAiThinkingExtractor 不存在）

- [ ] **Step 5: 实现**

`agent/src/main/java/com/javaagent/agent/stream/ThinkingExtractor.java`：

```java
package com.javaagent.agent.stream;

import org.springframework.ai.chat.model.ChatResponse;
import java.util.Optional;

public interface ThinkingExtractor {
    Optional<String> thinkingDelta(ChatResponse response);
    Optional<String> textDelta(ChatResponse response);
}
```

`agent/src/main/java/com/javaagent/agent/stream/OpenAiThinkingExtractor.java`：

```java
package com.javaagent.agent.stream;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从 OpenAI 兼容流式响应中区分 thinking 与正文。
 * 落点优先级（探针结论驱动，若 Step 2 结论不同，调整 CANDIDATE_KEYS 顺序即可）：
 * 1) result.output.metadata 中的 reasoningContent / reasoning_content / reasoning
 * 2) response.metadata 中同名键
 */
@Component
public class OpenAiThinkingExtractor implements ThinkingExtractor {

    static final List<String> CANDIDATE_KEYS = List.of("reasoningContent", "reasoning_content", "reasoning");

    @Override
    public Optional<String> thinkingDelta(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> outputMeta = response.getResults().get(0).getOutput().getMetadata();
        if (outputMeta != null) {
            for (String key : CANDIDATE_KEYS) {
                Object v = outputMeta.get(key);
                if (v instanceof String s && !s.isBlank()) {
                    return Optional.of(s);
                }
            }
        }
        if (response.getMetadata() != null && response.getMetadata().get("reasoningContent") instanceof String s) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> textDelta(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return Optional.empty();
        }
        String text = response.getResults().get(0).getOutput().getText();
        return (text == null || text.isEmpty()) ? Optional.empty() : Optional.of(text);
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
mvn -q -pl agent test -Dtest=OpenAiThinkingExtractorTest
```
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src
git commit -m "feat: thinking 流式探针与 ThinkingExtractor（reasoning/正文分流）"
```

---

### Task 4: FileTools（read/write/list + 路径安全）

**Files:**
- Create: `agent/src/main/java/com/javaagent/agent/tools/FileTools.java`
- Test: `agent/src/test/java/com/javaagent/agent/tools/FileToolsTest.java`
- Create: `workspace/.gitkeep`

**Interfaces:**
- Produces: `FileTools(Path workspaceRoot)`，方法 `read_file(String path)`、`write_file(String path, String content)`、`list_dir(String path)`（均挂 `@Tool`），`ToolCallback[] toCallbacks()`；路径安全入口 `Path resolveSafely(String relative)`（越界抛 `IllegalArgumentException`）

- [ ] **Step 1: 写失败测试**

`agent/src/test/java/com/javaagent/agent/tools/FileToolsTest.java`：

```java
package com.javaagent.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolsTest {

    @TempDir
    Path tempDir;
    private FileTools fileTools;

    @BeforeEach
    void setUp() {
        fileTools = new FileTools(tempDir);
    }

    @Test
    void readFileReturnsContent() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        assertThat(fileTools.read_file("a.txt")).isEqualTo("hello");
    }

    @Test
    void writeFileCreatesFileAndParentDirs() {
        fileTools.write_file("sub/b.txt", "内容");
        assertThat(tempDir.resolve("sub/b.txt")).hasContent("内容");
    }

    @Test
    void listDirReturnsEntryNames() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "x");
        Files.createDirectory(tempDir.resolve("d"));
        assertThat(fileTools.list_dir(".")).contains("a.txt", "d/");
    }

    @Test
    void pathEscapeIsRejected() {
        assertThatThrownBy(() -> fileTools.read_file("../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("路径越界");
    }

    @Test
    void absolutePathOutsideWorkspaceIsRejected() {
        assertThatThrownBy(() -> fileTools.read_file("/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingFileIsRejected() {
        assertThatThrownBy(() -> fileTools.read_file("nope.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    void toCallbacksExposesThreeTools() {
        List<String> names = java.util.Arrays.stream(fileTools.toCallbacks())
            .map(c -> c.getToolDefinition().name()).toList();
        assertThat(names).containsExactlyInAnyOrder("read_file", "write_file", "list_dir");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -q -pl agent test -Dtest=FileToolsTest
```
Expected: FAIL（FileTools 不存在）

- [ ] **Step 3: 实现**

`agent/src/main/java/com/javaagent/agent/tools/FileTools.java`：

```java
package com.javaagent.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区内文件读写工具。所有路径必须解析到工作区根之内，防目录穿越。
 */
public class FileTools {

    private final Path workspaceRoot;

    public FileTools(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Tool(description = "读取工作区内文本文件内容。path 为相对工作区根目录的路径")
    public String read_file(@ToolParam(description = "相对工作区根的文件路径") String path) {
        Path p = resolveSafely(path);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path + " - " + e.getMessage(), e);
        }
    }

    @Tool(description = "写入文本文件（UTF-8，覆盖已有内容），自动创建父目录。path 为相对工作区根目录的路径")
    public String write_file(@ToolParam(description = "相对工作区根的文件路径") String path,
                             @ToolParam(description = "要写入的完整文本内容") String content) {
        Path p = resolveSafely(path);
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content);
            return "已写入 " + workspaceRoot.relativize(p) + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            throw new IllegalStateException("写入失败: " + path + " - " + e.getMessage(), e);
        }
    }

    @Tool(description = "列出目录下的文件与子目录。path 为相对工作区根目录的路径，\".\" 表示根")
    public List<String> list_dir(@ToolParam(description = "相对工作区根的目录路径") String path) {
        Path p = resolveSafely(path);
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("目录不存在: " + path);
        }
        try (var stream = Files.list(p)) {
            List<String> names = new ArrayList<>();
            stream.sorted().forEach(entry ->
                names.add(entry.getFileName().toString() + (Files.isDirectory(entry) ? "/" : "")));
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("列目录失败: " + path + " - " + e.getMessage(), e);
        }
    }

    Path resolveSafely(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path p = workspaceRoot.resolve(relative).normalize();
        if (!p.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径越界: " + relative);
        }
        return p;
    }

    public ToolCallback[] toCallbacks() {
        return ToolCallbacks.from(this);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q -pl agent test -Dtest=FileToolsTest
```
Expected: PASS（7 个测试全绿；`/etc/passwd` 反例在 `normalize` 后不在 root 内被拒）

- [ ] **Step 5: Commit**

```bash
mkdir -p workspace && touch workspace/.gitkeep
git add agent/src workspace/.gitkeep
git commit -m "feat: 文件读写工具与路径安全校验"
```

---

### Task 5: 技能体系（frontmatter 解析 / 常驻注入 / 渐进过滤 / 示例技能）

**Files:**
- Create: `agent/src/main/java/com/javaagent/agent/skills/SkillDefinition.java`
- Create: `agent/src/main/java/com/javaagent/agent/skills/SkillManifestScanner.java`
- Create: `agent/src/main/java/com/javaagent/agent/skills/FilteredSkillRegistry.java`
- Create: `agent/src/main/java/com/javaagent/agent/skills/ResidentPromptBuilder.java`
- Test: `agent/src/test/java/com/javaagent/agent/skills/SkillManifestScannerTest.java`
- Test: `agent/src/test/java/com/javaagent/agent/skills/FilteredSkillRegistryTest.java`
- Create: `skills/java-coding-standards/SKILL.md`（resident）
- Create: `skills/markdown-report/SKILL.md`（progressive）
- Create: `skills/csv-analysis/SKILL.md`（progressive，绑 groupedTools）

**Interfaces:**
- Consumes: SAA `FileSystemSkillRegistry`、`SkillRegistry`（com.alibaba.cloud.ai.graph.skills.registry.*）
- Produces:
  - `record SkillDefinition(String name, String description, boolean resident, Path dir, String content)`
  - `SkillManifestScanner.scan(Path skillsRoot): List<SkillDefinition>`
  - `FilteredSkillRegistry implements SkillRegistry`（构造 `(SkillRegistry delegate, Set<String> residentNames)`，`listAll()/contains()/get()` 过滤 resident）
  - `ResidentPromptBuilder.build(): String`（常驻技能全文，拼入 system prompt 的 `{resident_skills}` 占位符）

- [ ] **Step 1: 示例技能文件**

`skills/java-coding-standards/SKILL.md`：

```markdown
---
name: java-coding-standards
description: Java 代码规范与工程约定，涉及写代码、重构、命名时使用
resident: true
---
# Java 编码规范

- 包名全小写；类名 PascalCase；方法/变量 camelCase
- 优先使用 Java 21 语法（record、sealed、switch pattern、文本块）
- 大文本不硬编码，文件化 + 启动加载缓存
- 多处复用的能力上提父类或公共服务
```

`skills/markdown-report/SKILL.md`：

```markdown
---
name: markdown-report
description: 需要产出结构化 Markdown 报告、总结、周报时使用
---
# Markdown 报告技能

生成报告时遵循：

1. 一级标题为报告主题，二级标题分节
2. 每节先结论后细节
3. 数据用表格呈现，来源写明
4. 末尾附"待确认事项"清单

## 参考资源
- references/report-template.md：报告模板（需要时用 read_file 读取 skills/markdown-report/references/report-template.md）
```

`skills/markdown-report/references/report-template.md`：

```markdown
# {标题}
## 摘要
{一段话结论}
## 详情
{分节内容}
## 待确认事项
- {item}
```

`skills/csv-analysis/SKILL.md`：

```markdown
---
name: csv-analysis
description: 分析 CSV 数据文件时使用，提供行数统计与表头识别工具
---
# CSV 分析技能

读取本技能后，可使用 csv_summary 工具快速统计 CSV 文件：

- 参数 path：相对工作区根的 CSV 文件路径
- 返回：总行数（不含表头）、列数、表头字段列表

典型流程：list_dir 定位文件 → csv_summary 统计 → 用结论回答用户。
```

- [ ] **Step 2: 写失败测试（扫描器）**

`agent/src/test/java/com/javaagent/agent/skills/SkillManifestScannerTest.java`：

```java
package com.javaagent.agent.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillManifestScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void scansSkillsAndParsesFrontmatter() throws Exception {
        Path skillDir = tempDir.resolve("demo-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: demo-skill
            description: 演示技能
            resident: true
            ---
            # 正文内容
            """);

        List<SkillDefinition> defs = new SkillManifestScanner().scan(tempDir);

        assertThat(defs).hasSize(1);
        SkillDefinition def = defs.get(0);
        assertThat(def.name()).isEqualTo("demo-skill");
        assertThat(def.description()).isEqualTo("演示技能");
        assertThat(def.resident()).isTrue();
        assertThat(def.content()).contains("# 正文内容");
        assertThat(def.dir()).isEqualTo(skillDir);
    }

    @Test
    void nonResidentSkillDefaultsToProgressive() throws Exception {
        Path skillDir = tempDir.resolve("progressive-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: progressive-skill
            description: 渐进式技能
            ---
            正文
            """);

        List<SkillDefinition> defs = new SkillManifestScanner().scan(tempDir);

        assertThat(defs.get(0).resident()).isFalse();
    }

    @Test
    void missingFrontmatterIsSkipped() throws Exception {
        Path skillDir = tempDir.resolve("bad-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "没有 frontmatter 的内容");

        assertThat(new SkillManifestScanner().scan(tempDir)).isEmpty();
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
mvn -q -pl agent test -Dtest=SkillManifestScannerTest
```
Expected: FAIL（类不存在）

- [ ] **Step 4: 实现扫描器**

`agent/src/main/java/com/javaagent/agent/skills/SkillDefinition.java`：

```java
package com.javaagent.agent.skills;

import java.nio.file.Path;

public record SkillDefinition(String name, String description, boolean resident, Path dir, String content) {
}
```

`agent/src/main/java/com/javaagent/agent/skills/SkillManifestScanner.java`：

```java
package com.javaagent.agent.skills;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫描技能目录，解析 SKILL.md frontmatter（name/description/resident）。
 * 解析结果由调用方缓存（SkillBootstrap 语义：启动扫描一次，配合 autoReload 重新扫描）。
 */
@Component
public class SkillManifestScanner {

    private static final Pattern FRONTMATTER =
        Pattern.compile("\\A---\\R(.*?)\\R---\\R?(.*)\\Z", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("^name:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern DESCRIPTION = Pattern.compile("^description:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern RESIDENT = Pattern.compile("^resident:\\s*true\\s*$", Pattern.MULTILINE);

    public List<SkillDefinition> scan(Path skillsRoot) {
        try (var stream = Files.list(skillsRoot)) {
            return stream.filter(Files::isDirectory)
                .map(dir -> dir.resolve("SKILL.md"))
                .filter(Files::isRegularFile)
                .map(this::parse)
                .flatMap(java.util.Optional::stream)
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描技能目录失败: " + skillsRoot, e);
        }
    }

    private java.util.Optional<SkillDefinition> parse(Path skillMd) {
        try {
            String raw = Files.readString(skillMd);
            Matcher m = FRONTMATTER.matcher(raw);
            if (!m.matches()) {
                return java.util.Optional.empty();
            }
            String frontmatter = m.group(1);
            String content = m.group(2);
            Matcher name = NAME.matcher(frontmatter);
            Matcher desc = DESCRIPTION.matcher(frontmatter);
            if (!name.find() || !desc.find()) {
                return java.util.Optional.empty();
            }
            boolean resident = RESIDENT.matcher(frontmatter).find();
            return java.util.Optional.of(new SkillDefinition(
                name.group(1).trim(), desc.group(1).trim(), resident, skillMd.getParent(), content));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }
}
```

- [ ] **Step 5: 运行确认通过**

```bash
mvn -q -pl agent test -Dtest=SkillManifestScannerTest
```
Expected: PASS

- [ ] **Step 6: 写失败测试（过滤注册表）**

`agent/src/test/java/com/javaagent/agent/skills/FilteredSkillRegistryTest.java`：

```java
package com.javaagent.agent.skills;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.SkillMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FilteredSkillRegistryTest {

    @TempDir
    Path skillsRoot;
    private SkillRegistry inner;
    private FilteredSkillRegistry filtered;

    @BeforeEach
    void setUp() throws Exception {
        writeSkill("resident-skill", true);
        writeSkill("progressive-skill", false);
        inner = com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry.builder()
            .projectSkillsDirectory(skillsRoot.toString())
            .build();
        filtered = new FilteredSkillRegistry(inner, Set.of("resident-skill"));
    }

    private void writeSkill(String name, boolean resident) throws Exception {
        Path dir = skillsRoot.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
            ---
            name: %s
            description: %s 技能
            %s
            ---
            %s 的正文
            """.formatted(name, name, resident ? "resident: true" : "", name));
    }

    @Test
    void listAllHidesResidentSkills() {
        List<SkillMetadata> all = filtered.listAll();
        assertThat(all).extracting(SkillMetadata::name).containsExactly("progressive-skill");
    }

    @Test
    void containsHidesResidentSkills() {
        assertThat(filtered.contains("resident-skill")).isFalse();
        assertThat(filtered.contains("progressive-skill")).isTrue();
    }

    @Test
    void sizeCountsOnlyProgressive() {
        assertThat(filtered.size()).isEqualTo(1);
    }

    @Test
    void readSkillContentStillWorksForProgressive() {
        Optional<String> content = filtered.readSkillContent("progressive-skill");
        assertThat(content).contains("progressive-skill 的正文");
    }
}
```

注：`SkillMetadata` 的取 name 方法若非 `name()`（编译期核对，可能是 `getName()`），同步调整测试断言。

- [ ] **Step 7: 实现过滤注册表与常驻提示构建器**

`agent/src/main/java/com/javaagent/agent/skills/FilteredSkillRegistry.java`：

```java
package com.javaagent.agent.skills;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 委托 FileSystemSkillRegistry，但对 SkillsAgentHook 隐藏常驻技能：
 * 常驻技能全文已注入 system prompt，无需再走 read_skill 渐进披露。
 */
public class FilteredSkillRegistry implements SkillRegistry {

    private final SkillRegistry delegate;
    private final Set<String> residentNames;

    public FilteredSkillRegistry(SkillRegistry delegate, Set<String> residentNames) {
        this.delegate = delegate;
        this.residentNames = Set.copyOf(residentNames);
    }

    @Override
    public List<com.alibaba.cloud.ai.graph.skills.registry.SkillMetadata> listAll() {
        return delegate.listAll().stream()
            .filter(m -> !residentNames.contains(m.name()))
            .toList();
    }

    @Override
    public boolean contains(String skillName) {
        return !residentNames.contains(skillName) && delegate.contains(skillName);
    }

    @Override
    public Optional<String> get(String skillName) {
        if (residentNames.contains(skillName)) {
            return Optional.empty();
        }
        return delegate.get(skillName);
    }

    @Override
    public int size() {
        return listAll().size();
    }

    @Override
    public Optional<String> readSkillContent(String skillName) {
        if (residentNames.contains(skillName)) {
            return Optional.empty();
        }
        return delegate.readSkillContent(skillName);
    }

    @Override
    public String getSkillLoadInstructions() {
        return delegate.getSkillLoadInstructions();
    }

    @Override
    public String getRegistryType() {
        return delegate.getRegistryType();
    }

    @Override
    public String getSystemPromptTemplate() {
        return delegate.getSystemPromptTemplate();
    }

    @Override
    public void reload() {
        delegate.reload();
    }
}
```

`agent/src/main/java/com/javaagent/agent/skills/ResidentPromptBuilder.java`：

```java
package com.javaagent.agent.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 常驻技能全文（拼入 system prompt 静态区）。system prompt 模板文件化，
 * {resident_skills} 占位符在加载时替换，结果缓存。
 */
@Component
public class ResidentPromptBuilder {

    private final SkillManifestScanner scanner;
    private final Path skillsRoot;
    private final Path promptTemplate;
    private volatile String cached;

    public ResidentPromptBuilder(SkillManifestScanner scanner,
                                 @Value("${agent.skills-root:./skills}") String skillsRoot,
                                 @Value("${agent.prompt-template:prompts/system-prompt.md}") String promptTemplate) {
        this.scanner = scanner;
        this.skillsRoot = Path.of(skillsRoot);
        this.promptTemplate = Path.of("agent/src/main/resources").resolve(promptTemplate)
            .isAbsolute() ? Path.of(promptTemplate) : classpathOrFile(promptTemplate);
        this.cached = build();
    }

    private Path classpathOrFile(String path) {
        var resource = getClass().getClassLoader().getResource(path);
        return resource != null ? Path.of(resource.getFile()) : Path.of(path);
    }

    public String build() {
        String snapshot = cached;
        if (snapshot != null && !snapshot.isBlank()) {
            return snapshot;
        }
        try {
            String template = Files.readString(promptTemplate);
            List<SkillDefinition> resident = scanner.scan(skillsRoot).stream()
                .filter(SkillDefinition::resident).toList();
            String block = resident.stream()
                .map(d -> "### 技能：" + d.name() + "\n" + d.content().strip())
                .collect(Collectors.joining("\n\n"));
            return template.replace("{resident_skills}", block);
        } catch (IOException e) {
            throw new IllegalStateException("加载 system prompt 模板失败", e);
        }
    }

    /** autoReload 场景：清缓存后下次 build 重新扫描 */
    public void invalidateCache() {
        cached = null;
    }
}
```

- [ ] **Step 8: 运行确认通过**

```bash
mvn -q -pl agent test -Dtest=FilteredSkillRegistryTest -Dtest+=SkillManifestScannerTest
```
Expected: PASS（若 SkillMetadata.name() 签名不同，按 Step 6 注释调整后应全绿）

- [ ] **Step 9: Commit**

```bash
git add agent/src skills/
git commit -m "feat: 技能体系（frontmatter 解析/常驻注入/渐进过滤）与三个示例技能"
```

---

### Task 6: ChatModel 与 ReactAgent 装配（AgentFactory）

**Files:**
- Create: `agent/src/main/java/com/javaagent/agent/config/AgentBeansConfig.java`
- Create: `agent/src/main/java/com/javaagent/agent/agent/AgentFactory.java`
- Create: `agent/src/main/java/com/javaagent/agent/stream/ThinkingTapChatModel.java`
- Test: `agent/src/test/java/com/javaagent/agent/agent/AgentFactoryTest.java`
- Test: `agent/src/test/java/com/javaagent/agent/stream/ThinkingTapChatModelTest.java`

**Interfaces:**
- Consumes: `ChatModel`（自动装配）、`ThinkingExtractor`、`FileTools`、`SkillManifestScanner`、`ResidentPromptBuilder`、SAA `SkillsAgentHook`/`FileSystemSkillRegistry`/`ShellToolAgentHook`/`ShellTool2`/`MemorySaver`
- Produces:
  - `AgentFactory.create(Sinks.Many<AgentEvent> turnSink, Long turnId): ReactAgent`（每轮新实例；saver 为共享单例 Bean）
  - `ThinkingTapChatModel implements ChatModel`（装饰器：stream 时把 thinking 增量旁路发到 turnSink，usage 记录到 AtomicReference）

- [ ] **Step 1: 写失败测试（ThinkingTap）**

`agent/src/test/java/com/javaagent/agent/stream/ThinkingTapChatModelTest.java`：

```java
package com.javaagent.agent.stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingTapChatModelTest {

    private final ThinkingExtractor extractor = new OpenAiThinkingExtractor();
    private final ChatModelStub delegate = new ChatModelStub();

    private ChatResponse chunk(String text, Map<String, Object> meta) {
        return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(text, meta))));
    }

    @Test
    void thinkingDeltasAreEmittedToSink() {
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<Object> usage = new AtomicReference<>();
        delegate.next = Flux.just(
            chunk(null, Map.of("reasoningContent", "思考A")),
            chunk(null, Map.of("reasoningContent", "思考B")),
            chunk("正文", Map.of()));

        ThinkingTapChatModel tapped = new ThinkingTapChatModel(delegate, sink, extractor, usage);

        tapped.stream(new Prompt("hi")).blockLast();

        assertThat(sink.asFlux().collectList().block())
            .extracting(Object::toString)
            .containsExactly("思考A", "思考B");
    }

    /** 最小可用的 ChatModel 桩：stream 返回 next，call 返回单条 */
    static class ChatModelStub implements org.springframework.ai.chat.model.ChatModel {
        Flux<ChatResponse> next = Flux.empty();

        @Override
        public ChatResponse call(Prompt prompt) {
            return chunk("ok", Map.of());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return next;
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -pl agent test -Dtest=ThinkingTapChatModelTest
```
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 ThinkingTapChatModel**

`agent/src/main/java/com/javaagent/agent/stream/ThinkingTapChatModel.java`：

```java
package com.javaagent.agent.stream;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 装饰器：把流式响应中的 thinking 增量旁路发布到 turnSink（String），
 * 正文照常透传给 ReactAgent 的 LLM 节点。
 * SAA 的 LLM 节点会把 ChatResponse 归并为文本 chunk，thinking 不会出现在 NodeOutput 里，
 * 因此必须在 ChatModel 层拦截 —— 这是 thinking 链路的兜底方案（spec §8 风险预案）。
 */
public class ThinkingTapChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Sinks.Many<Object> thinkingSink;
    private final ThinkingExtractor extractor;
    private final AtomicReference<Object> usageCapture;

    public ThinkingTapChatModel(ChatModel delegate, Sinks.Many<Object> thinkingSink,
                                ThinkingExtractor extractor, AtomicReference<Object> usageCapture) {
        this.delegate = delegate;
        this.thinkingSink = thinkingSink;
        this.extractor = extractor;
        this.usageCapture = usageCapture;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
            .doOnNext(response -> {
                extractor.thinkingDelta(response)
                    .ifPresent(delta -> thinkingSink.tryEmitNext(delta));
                if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    usageCapture.set(response.getMetadata().getUsage());
                }
            });
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn -q -pl agent test -Dtest=ThinkingTapChatModelTest
```
Expected: PASS

- [ ] **Step 5: 装配 Bean 与 AgentFactory**

`agent/src/main/java/com/javaagent/agent/config/AgentBeansConfig.java`：

```java
package com.javaagent.agent.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.javaagent.agent.skills.FilteredSkillRegistry;
import com.javaagent.agent.skills.SkillManifestScanner;
import com.javaagent.agent.tools.FileTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Set;

@Configuration
public class AgentBeansConfig {

    @Bean
    public MemorySaver checkpointSaver() {
        // Task 10 替换为 PG JDBC saver（依赖注入处只依赖 Saver 基类/接口）
        return new MemorySaver();
    }

    @Bean
    public FileTools fileTools(@Value("${agent.workspace-root:./workspace}") String workspaceRoot) {
        return new FileTools(Path.of(workspaceRoot).toAbsolutePath().normalize());
    }

    @Bean
    public FilteredSkillRegistry progressiveSkillRegistry(
            SkillManifestScanner scanner,
            @Value("${agent.skills-root:./skills}") String skillsRoot) {
        var inner = com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry.builder()
            .projectSkillsDirectory(Path.of(skillsRoot).toAbsolutePath().toString())
            .build();
        Set<String> residentNames = Set.copyOf(scanner.scan(Path.of(skillsRoot)).stream()
            .filter(com.javaagent.agent.skills.SkillDefinition::resident)
            .map(com.javaagent.agent.skills.SkillDefinition::name)
            .toList());
        return new FilteredSkillRegistry(inner, residentNames);
    }
}
```

`agent/src/main/java/com/javaagent/agent/agent/AgentFactory.java`：

```java
package com.javaagent.agent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellTool2;
import com.javaagent.agent.skills.FilteredSkillRegistry;
import com.javaagent.agent.skills.ResidentPromptBuilder;
import com.javaagent.agent.stream.ThinkingExtractor;
import com.javaagent.agent.stream.ThinkingTapChatModel;
import com.javaagent.agent.tools.CsvSummaryTool;
import com.javaagent.agent.tools.FileTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每轮对话构建独立 ReactAgent 实例：便于注入该轮的 thinking 旁路 Sink。
 * saver（记忆）、技能注册表、工具均为共享 Bean，跨轮生效。
 */
@Component
public class AgentFactory {

    private final ChatModel chatModel;
    private final ThinkingExtractor thinkingExtractor;
    private final FileTools fileTools;
    private final FilteredSkillRegistry skillRegistry;
    private final ResidentPromptBuilder residentPromptBuilder;
    private final Object checkpointSaver;  // Task 10 定型为具体 Saver 类型
    private final String workspaceRoot;

    public AgentFactory(ChatModel chatModel, ThinkingExtractor thinkingExtractor,
                        FileTools fileTools, FilteredSkillRegistry skillRegistry,
                        ResidentPromptBuilder residentPromptBuilder, Object checkpointSaver,
                        @org.springframework.beans.factory.annotation.Value("${agent.workspace-root:./workspace}") String workspaceRoot) {
        this.chatModel = chatModel;
        this.thinkingExtractor = thinkingExtractor;
        this.fileTools = fileTools;
        this.skillRegistry = skillRegistry;
        this.residentPromptBuilder = residentPromptBuilder;
        this.checkpointSaver = checkpointSaver;
        this.workspaceRoot = workspaceRoot;
    }

    public record AgentHandle(ReactAgent agent, ThinkingTapChatModel tappedModel) {}

    public AgentHandle create(Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture) {
        ThinkingTapChatModel tapped = new ThinkingTapChatModel(chatModel, thinkingSink, thinkingExtractor, usageCapture);

        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
            .skillRegistry(skillRegistry)
            .autoReload(true)
            .groupedTools(java.util.Map.of("csv-analysis", List.of(CsvSummaryTool.callback(fileTools.workspace()))))
            .build();

        ShellToolAgentHook shellHook = ShellToolAgentHook.builder()
            .shellTool2(ShellTool2.builder(workspaceRoot).build())
            .build();

        ReactAgent agent = ReactAgent.builder()
            .name("java-agent")
            .model(tapped)
            .systemPrompt(residentPromptBuilder.build())
            .tools(fileTools.toCallbacks())
            .hooks(List.of(skillsHook, shellHook))
            .saver((com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver) checkpointSaver)
            .build();

        return new AgentHandle(agent, tapped);
    }
}
```

注：`BaseCheckpointSaver` 具体类型名、`ShellTool2.builder(String)` 签名、`groupedTools` Map 泛型、`CsvSummaryTool`（Task 7 产出）编译期按 SAA 1.1.2.3 源码核对微调；`FileTools.workspace()` 需在 Task 4 基础上补一个包内可见的 `Path workspace()` 访问器（ CsvSummaryTool 与 shell 共用工作区根）。本步骤先补：

```java
// FileTools 追加
Path workspace() {
    return workspaceRoot;
}
```

- [ ] **Step 6: 写失败测试（AgentFactory 装配冒烟）**

`agent/src/test/java/com/javaagent/agent/agent/AgentFactoryTest.java`：

```java
package com.javaagent.agent.agent;

import com.javaagent.agent.skills.ResidentPromptBuilder;
import com.javaagent.agent.skills.SkillManifestScanner;
import com.javaagent.agent.stream.OpenAiThinkingExtractor;
import com.javaagent.agent.tools.FileTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createBuildsAgentWithResidentSkillsInSystemPrompt() throws Exception {
        // 复用真实 skills 目录太重，这里临时构造一个最小技能集
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot.resolve("r-skill"));
        Files.writeString(skillsRoot.resolve("r-skill/SKILL.md"), """
            ---
            name: r-skill
            description: 常驻演示
            resident: true
            ---
            常驻技能正文
            """);
        Files.createDirectories(tempDir.resolve("workspace"));

        ResidentPromptBuilder promptBuilder = new ResidentPromptBuilder(
            new SkillManifestScanner(), skillsRoot.toString(), "prompts/system-prompt.md");

        AgentFactory factory = new AgentFactory(
            new com.javaagent.agent.stream.ThinkingTapChatModelTest.ChatModelStub(),
            new OpenAiThinkingExtractor(),
            new FileTools(tempDir.resolve("workspace")),
            null, // skillRegistry 在本测试不参与断言
            promptBuilder,
            new com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver(),
            tempDir.resolve("workspace").toString());

        AgentFactory.AgentHandle handle =
            factory.create(Sinks.many().unicast().onBackpressureBuffer(), new AtomicReference<>());

        assertThat(handle.agent()).isNotNull();
    }
}
```

注：`ResidentPromptBuilder` 构造在测试里用临时模板 —— 若 `classpathOrFile` 解析 `prompts/system-prompt.md` 在测试 classpath 下可用（agent 模块 main resources 会在 test classpath），直接命中真实模板，无需额外构造。断言聚焦 `agent` 创建成功与 systemPrompt 含常驻技能正文（如可从 agent 取出 systemPrompt，则加 `assertThat(...).contains("常驻技能正文")`，取出方式编译期核对 ReactAgent API）。

- [ ] **Step 7: 运行确认通过**

```bash
mvn -q -pl agent test -Dtest=AgentFactoryTest -Dtest+=ThinkingTapChatModelTest
```
Expected: PASS（允许因 SAA 签名差异微调后通过）

- [ ] **Step 8: Commit**

```bash
git add agent/src
git commit -m "feat: AgentFactory 每轮装配 ReactAgent（thinking 旁路 + 技能 + Shell/文件工具）"
```

---

### Task 7: 持久化仓储（实体/Repository）+ CsvSummaryTool

**Files:**
- Create: `agent/src/main/java/com/javaagent/agent/persistence/Conversation.java`
- Create: `agent/src/main/java/com/javaagent/agent/persistence/Turn.java`
- Create: `agent/src/main/java/com/javaagent/agent/persistence/Message.java`
- Create: `agent/src/main/java/com/javaagent/agent/persistence/ConversationRepository.java`
- Create: `agent/src/main/java/com/javaagent/agent/persistence/TurnRepository.java`
- Create: `agent/src/main/java/com/javaagent/agent/persistence/MessageRepository.java`
- Create: `agent/src/main/java/com/javaagent/agent/tools/CsvSummaryTool.java`
- Test: `agent/src/test/java/com/javaagent/agent/persistence/RepositoryTest.java`
- Test: `agent/src/test/java/com/javaagent/agent/tools/CsvSummaryToolTest.java`

**Interfaces:**
- Produces:
  - 实体（Spring Data JDBC，字段与 V1 DDL 一一对应）：`Conversation(Long id, String title, String threadId, Instant createdAt, Instant updatedAt)`、`Turn(Long id, Long conversationId, int seq, String status, String finishReason, String usage, Instant startedAt, Instant finishedAt)`、`Message(Long id, Long turnId, int seq, String msgType, String content, String callId, String toolName, String arguments, String result, Boolean success, Long durationMs, Instant createdAt)`
  - `ConversationRepository extends CrudRepository<Conversation, Long>`：`List<Conversation> findAllByOrderByUpdatedAtDesc()`、`@Query("update conversation set updated_at = now() where id = :id") void touch(Long id)`
  - `TurnRepository extends CrudRepository<Turn, Long>`：`int countByConversationId(Long conversationId)`、`Optional<Turn> findTopByConversationIdOrderBySeqDesc(Long conversationId)`
  - `MessageRepository extends CrudRepository<Message, Long>`：`List<Message> findByTurnIdOrderBySeq(Long turnId)`、`List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId)`（后者需 join turn，用 @Query）
  - `CsvSummaryTool.callback(Path workspaceRoot): ToolCallback`（工具名 `csv_summary`，参数 `path`，返回 "总行数(不含表头)X，列数Y，表头: a,b,c"）

- [ ] **Step 1: 写失败测试（仓储）**

`agent/src/test/java/com/javaagent/agent/persistence/RepositoryTest.java`：

```java
package com.javaagent.agent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJdbcTest
@Testcontainers
class RepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ConversationRepository conversations;
    @Autowired
    TurnRepository turns;
    @Autowired
    MessageRepository messages;

    @Test
    void saveAndQueryConversationTurnMessage() {
        Conversation conv = conversations.save(new Conversation(null, "测试会话", "conv-1", Instant.now(), Instant.now()));

        Turn turn = turns.save(new Turn(null, conv.id(), 1, "RUNNING",
            null, null, Instant.now(), null));

        messages.save(new Message(null, turn.id(), 0, "USER", "你好", null, null, null, null, null, null, Instant.now()));
        messages.save(new Message(null, turn.id(), 1, "THINKING", "思考内容", null, null, null, null, null, null, Instant.now()));
        messages.save(new Message(null, turn.id(), 2, "TOOL_CALL", null, "call-1", "list_dir",
            "{\"path\":\".\"}", null, null, null, Instant.now()));
        messages.save(new Message(null, turn.id(), 3, "TOOL_RESULT", null, "call-1", "list_dir",
            null, "a.txt\nb/", true, 120L, Instant.now()));

        assertThat(turns.countByConversationId(conv.id())).isEqualTo(1);
        List<Message> turnMessages = messages.findByTurnIdOrderBySeq(turn.id());
        assertThat(turnMessages).hasSize(4).extracting(Message::msgType)
            .containsExactly("USER", "THINKING", "TOOL_CALL", "TOOL_RESULT");

        List<Message> timeline = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(timeline).hasSize(4);
    }
}
```

注：`@DataJdbcTest` 不自动跑 Flyway（用 schema.sql 或手动初始化）。给测试加 `src/test/resources/schema.sql`：直接 `COPY` V1 的 DDL（或 `spring.sql.init.schema-locations` 指向 `classpath:db/migration/V1__init.sql`，在 `@DataJdbcTest` 的 properties 里配置 `spring.sql.init.schema-locations=classpath:db/migration/V1__init.sql`，推荐后者避免 DDL 双份维护）。Flyway 迁移文件在 web 模块，agent 测试读不到 —— 因此把 `db/migration/` 目录挪到 **agent 模块 resources**（spec 的"web 模块 resources/db/migration"修正为 agent 模块持有 DDL、web 启动时执行；迁移文件位置变更不算偏离 spec，Flyway 配置仍由 web 的 application.yml 驱动）。Task 2 的文件相应从 `web/src/main/resources/db/migration/V1__init.sql` 移到 `agent/src/main/resources/db/migration/V1__init.sql`，两个模块的 test 均用 `spring.sql.init.schema-locations=classpath:db/migration/V1__init.sql`。

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -pl agent test -Dtest=RepositoryTest
```
Expected: FAIL（实体/仓储不存在）

- [ ] **Step 3: 实现实体与仓储**

实体用 record + Spring Data JDBC `@Table`（1.x 支持 record 不可变实体，若构造注入复杂则退化为普通类 + Lombok 不引入，直接手写类）：

`agent/src/main/java/com/javaagent/agent/persistence/Conversation.java`：

```java
package com.javaagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("conversation")
public record Conversation(@Id Long id, String title, String threadId,
                            Instant createdAt, Instant updatedAt) {
}
```

`agent/src/main/java/com/javaagent/agent/persistence/Turn.java`：

```java
package com.javaagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("turn")
public record Turn(@Id Long id, Long conversationId, int seq, String status,
                   String finishReason, String usage, Instant startedAt, Instant finishedAt) {

    public static Turn running(Long conversationId, int seq) {
        return new Turn(null, conversationId, seq, "RUNNING", null, null, Instant.now(), null);
    }

    public Turn complete(String finishReason, String usageJson) {
        return new Turn(id, conversationId, seq, "COMPLETED", finishReason, usageJson, startedAt, Instant.now());
    }

    public Turn fail(String reason) {
        return new Turn(id, conversationId, seq, "FAILED", reason, usage, startedAt, Instant.now());
    }
}
```

`agent/src/main/java/com/javaagent/agent/persistence/Message.java`：

```java
package com.javaagent.agent.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("message")
public record Message(@Id Long id, Long turnId, int seq, String msgType, String content,
                      String callId, String toolName, String arguments, String result,
                      Boolean success, Long durationMs, Instant createdAt) {

    public static Message user(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "USER", content, null, null, null, null, null, null, Instant.now());
    }

    public static Message thinking(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "THINKING", content, null, null, null, null, null, null, Instant.now());
    }

    public static Message text(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "TEXT", content, null, null, null, null, null, null, Instant.now());
    }

    public static Message toolCall(Long turnId, int seq, String callId, String toolName, String arguments) {
        return new Message(null, turnId, seq, "TOOL_CALL", null, callId, toolName, arguments, null, null, null, Instant.now());
    }

    public static Message toolResult(Long turnId, int seq, String callId, String toolName,
                                     String result, boolean success, long durationMs) {
        return new Message(null, turnId, seq, "TOOL_RESULT", null, callId, toolName, null,
            result, success, durationMs, Instant.now());
    }

    public static Message error(Long turnId, int seq, String content) {
        return new Message(null, turnId, seq, "ERROR", content, null, null, null, null, null, null, Instant.now());
    }

    public static Message summary(Long conversationIdTurnIdIgnored, int seq, String content) {
        return new Message(null, null, seq, "SUMMARY", content, null, null, null, null, null, null, Instant.now());
    }
}
```

注：`summary()` 工厂供 CompactionService 使用，落库时 turnId 由调用方显式设置（summary 存在独立轮次或会话级表，见 Task 11 的 V2 决策）；record 的 `turnId` 不可变，压缩时用 new 构造即可，删掉该工厂方法也可以。

`agent/src/main/java/com/javaagent/agent/persistence/ConversationRepository.java`：

```java
package com.javaagent.agent.persistence;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;

import java.util.List;

public interface ConversationRepository extends CrudRepository<Conversation, Long> {

    List<Conversation> findAllByOrderByUpdatedAtDesc();

    @Modifying
    @Query("UPDATE conversation SET updated_at = now() WHERE id = :id")
    void touch(Long id);
}
```

`agent/src/main/java/com/javaagent/agent/persistence/TurnRepository.java`：

```java
package com.javaagent.agent.persistence;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface TurnRepository extends CrudRepository<Turn, Long> {

    int countByConversationId(Long conversationId);

    Optional<Turn> findTopByConversationIdOrderBySeqDesc(Long conversationId);
}
```

`agent/src/main/java/com/javaagent/agent/persistence/MessageRepository.java`：

```java
package com.javaagent.agent.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface MessageRepository extends CrudRepository<Message, Long> {

    List<Message> findByTurnIdOrderBySeq(Long turnId);

    @Query("SELECT m.* FROM message m JOIN turn t ON m.turn_id = t.id " +
           "WHERE t.conversation_id = :conversationId ORDER BY t.seq ASC, m.seq ASC")
    List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId);
}
```

`agent/src/main/resources/application-shared.yml` 不需要——agent 测试用注解属性配置（见 Step 1 注）。

- [ ] **Step 4: 运行仓储测试确认通过**

```bash
mvn -q -pl agent test -Dtest=RepositoryTest
```
Expected: PASS

- [ ] **Step 5: 写失败测试（CsvSummaryTool）**

`agent/src/test/java/com/javaagent/agent/tools/CsvSummaryToolTest.java`：

```java
package com.javaagent.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CsvSummaryToolTest {

    @TempDir
    Path tempDir;
    private CsvSummaryTool tool;

    @BeforeEach
    void setUp() {
        tool = new CsvSummaryTool(tempDir);
    }

    @Test
    void summarizesCsv() throws Exception {
        Files.writeString(tempDir.resolve("data.csv"), "name,age\nAlice,30\nBob,25\n");
        String result = tool.csv_summary("data.csv");
        assertThat(result).contains("2", "2", "name, age");
    }

    @Test
    void callbackNameIsCsvSummary() {
        assertThat(tool.callback().getToolDefinition().name()).isEqualTo("csv_summary");
    }
}
```

- [ ] **Step 6: 实现 CsvSummaryTool**

`agent/src/main/java/com/javaagent/agent/tools/CsvSummaryTool.java`：

```java
package com.javaagent.agent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.ToolCallbacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * csv-analysis 技能的专属工具（groupedTools 绑定，read_skill 后才暴露）。
 */
public class CsvSummaryTool {

    private final Path workspaceRoot;

    public CsvSummaryTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Tool(description = "统计 CSV 文件：返回总行数（不含表头）、列数、表头字段列表")
    public String csv_summary(@ToolParam(description = "相对工作区根的 CSV 文件路径") String path) {
        Path p = new FileTools(workspaceRoot).resolveSafely(path);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        try {
            var lines = Files.readAllLines(p);
            if (lines.isEmpty() || lines.get(0).isBlank()) {
                return "空文件";
            }
            String[] header = lines.get(0).split(",");
            long dataRows = lines.size() - 1;
            return "总行数(不含表头) %d，列数 %d，表头: %s"
                .formatted(dataRows, header.length, String.join(", ", header));
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }

    public ToolCallback callback() {
        return Arrays.stream(ToolCallbacks.from(this)).findFirst().orElseThrow();
    }
}
```

- [ ] **Step 7: 运行全部 agent 测试**

```bash
mvn -q -pl agent test -DexcludedGroups=manual
```
Expected: PASS（含之前所有任务的测试，回归无破坏）

- [ ] **Step 8: Commit**

```bash
git add agent/src
git commit -m "feat: 会话/轮次/消息仓储与 csv_summary 技能工具"
```

---

### Task 8: AgentEvent 领域事件 + AgentFacade（流式映射与落库核心）

**Files:**
- Create: `agent/src/main/java/com/javaagent/agent/facade/AgentEvent.java`
- Create: `agent/src/main/java/com/javaagent/agent/facade/AgentFacade.java`
- Create: `agent/src/main/java/com/javaagent/agent/facade/SegmentBuffer.java`
- Create: `agent/src/main/java/com/javaagent/agent/agent/EventEmittingToolInterceptor.java`
- Test: `agent/src/test/java/com/javaagent/agent/facade/SegmentBufferTest.java`
- Test: `agent/src/test/java/com/javaagent/agent/facade/AgentFacadeTest.java`

**Interfaces:**
- Consumes: `AgentFactory`、三个 Repository、`Conversation.threadId`
- Produces（web 模块将依赖的稳定契约）:

```java
public sealed interface AgentEvent {
    Long turnId();
    record Meta(Long turnId, Long conversationId, String model) implements AgentEvent {}
    record ThinkingDelta(Long turnId, String content) implements AgentEvent {}
    record MessageDelta(Long turnId, String content) implements AgentEvent {}
    record ToolCall(Long turnId, String callId, String toolName, String arguments) implements AgentEvent {}
    record ToolResult(Long turnId, String callId, String toolName, String result, long durationMs, boolean success) implements AgentEvent {}
    record TurnDone(Long turnId, String finishReason, Usage usage) implements AgentEvent {}
    record TurnError(Long turnId, String code, String message) implements AgentEvent {}
    record Usage(long promptTokens, long completionTokens, long totalTokens) {}
}
```

`public Flux<AgentEvent> chat(Long conversationId, String content)`（AgentFacade 唯一入口）
`SegmentBuffer`：thinking/text 增量累积，边界 flush 完整 Message 落库（对外发 delta 事件不缓存内容重复）

**事件通路设计（与 spec §5 一致）：**
- thinking delta：`ThinkingTapChatModel` → thinkingSink（String）→ facade 转 `ThinkingDelta`，同时累积进 SegmentBuffer
- 工具事件：`EventEmittingToolInterceptor` → turnSink 直发 `ToolCall`/`ToolResult`（含耗时与成功标记；异常时按 spec §7 回传 `Tool failed: ...` 给模型，不中断 ReAct 循环）
- 正文 delta：`agent.stream(...)` 的 `Flux<NodeOutput>`（LLM 节点 StreamingOutput chunk）
- turn 收尾：`doFinally` 里 flush 残留 segment、turn 置 COMPLETED/FAILED、发 `TurnDone`/`TurnError`

- [ ] **Step 1: AgentEvent 定义**

`agent/src/main/java/com/javaagent/agent/facade/AgentEvent.java`：

```java
package com.javaagent.agent.facade;

/**
 * agent 引擎对外统一事件流（框架无关，web 层转 SSE）。
 */
public sealed interface AgentEvent {

    Long turnId();

    record Meta(Long turnId, Long conversationId, String model) implements AgentEvent {}

    record ThinkingDelta(Long turnId, String content) implements AgentEvent {}

    record MessageDelta(Long turnId, String content) implements AgentEvent {}

    record ToolCall(Long turnId, String callId, String toolName, String arguments) implements AgentEvent {}

    record ToolResult(Long turnId, String callId, String toolName, String result,
                      long durationMs, boolean success) implements AgentEvent {}

    record TurnDone(Long turnId, String finishReason, Usage usage) implements AgentEvent {}

    record TurnError(Long turnId, String code, String message) implements AgentEvent {}

    record Usage(long promptTokens, long completionTokens, long totalTokens) {}
}
```

- [ ] **Step 2: 写失败测试（SegmentBuffer）**

`agent/src/test/java/com/javaagent/agent/facade/SegmentBufferTest.java`：

```java
package com.javaagent.agent.facade;

import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentBufferTest {

    static class RecordingRepo implements MessageRepository {
        List<Message> saved = new ArrayList<>();
        @Override public <S extends Message> S save(S entity) { saved.add(entity); return entity; }
        @Override public <S extends Message> Iterable<S> saveAll(Iterable<S> entities) { return entities; }
        @Override public java.util.Optional<Message> findById(Long aLong) { return java.util.Optional.empty(); }
        @Override public boolean existsById(Long aLong) { return false; }
        @Override public List<Message> findAll() { return List.of(); }
        @Override public Iterable<Message> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void deleteById(Long aLong) {}
        @Override public void delete(Message entity) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends Message> entities) {}
        @Override public void deleteAll() {}
        @Override public List<Message> findByTurnIdOrderBySeq(Long turnId) { return List.of(); }
        @Override public List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId) { return List.of(); }
    }

    @Test
    void thinkingAccumulatesAndFlushesAsOneMessage() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.appendThinking("思考A");
        buffer.appendThinking("思考B");
        buffer.flushThinking();  // 段落边界

        assertThat(repo.saved).hasSize(1);
        Message saved = repo.saved.get(0);
        assertThat(saved.msgType()).isEqualTo("THINKING");
        assertThat(saved.content()).isEqualTo("思考A思考B");
        assertThat(saved.seq()).isEqualTo(1);  // seq=0 是 USER
    }

    @Test
    void newThinkingSegmentAfterToolUsesPreviousSeq() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.appendThinking("第一段");
        buffer.flushThinking();
        buffer.recordToolCall("call-1", "list_dir", "{}");
        buffer.appendThinking("第二段");
        buffer.flushThinking();

        assertThat(repo.saved).extracting(Message::seq).containsExactly(1, 2, 3);
        assertThat(repo.saved).extracting(Message::msgType)
            .containsExactly("THINKING", "TOOL_CALL", "THINKING");
    }

    @Test
    void toolResultIsSavedImmediately() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.recordToolResult("call-1", "list_dir", "结果", true, 100L);

        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).success()).isTrue();
        assertThat(repo.saved.get(0).durationMs()).isEqualTo(100L);
    }

    @Test
    void emptySegmentsAreNotSaved() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.flushThinking();
        buffer.flushText();

        assertThat(repo.saved).isEmpty();
    }

    @Test
    void flushAllWritesPendingSegments() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.appendText("正文");
        buffer.flushAll();

        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).msgType()).isEqualTo("TEXT");
    }
}
```

- [ ] **Step 3: 运行确认失败**

```bash
mvn -q -pl agent test -Dtest=SegmentBufferTest
```
Expected: FAIL

- [ ] **Step 4: 实现 SegmentBuffer**

`agent/src/main/java/com/javaagent/agent/facade/SegmentBuffer.java`：

```java
package com.javaagent.agent.facade;

import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;

/**
 * 增量累积 → 段落边界 flush 完整 Message（spec：不存 delta，存完整消息）。
 * seq=0 固定为 USER 消息（由 facade 在创建 buffer 前落库），内部从 1 起。
 */
public class SegmentBuffer {

    private final Long turnId;
    private final MessageRepository repository;
    private int seq;
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder text = new StringBuilder();

    public SegmentBuffer(Long turnId, MessageRepository repository) {
        this.turnId = turnId;
        this.repository = repository;
        this.seq = 1;
    }

    public void appendThinking(String delta) {
        thinking.append(delta);
    }

    public void appendText(String delta) {
        text.append(delta);
    }

    public void flushThinking() {
        if (!thinking.isEmpty()) {
            repository.save(Message.thinking(turnId, nextSeq(), thinking.toString()));
            thinking.setLength(0);
        }
    }

    public void flushText() {
        if (!text.isEmpty()) {
            repository.save(Message.text(turnId, nextSeq(), text.toString()));
            text.setLength(0);
        }
    }

    public void recordToolCall(String callId, String toolName, String arguments) {
        flushThinking();
        flushText();
        repository.save(Message.toolCall(turnId, nextSeq(), callId, toolName, arguments));
    }

    public void recordToolResult(String callId, String toolName, String result,
                                 boolean success, long durationMs) {
        repository.save(Message.toolResult(turnId, nextSeq(), callId, toolName,
            truncate(result), success, durationMs));
    }

    public void flushAll() {
        flushThinking();
        flushText();
    }

    private int nextSeq() {
        return seq++;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 10_000 ? s.substring(0, 10_000) + "\n...[截断]" : s;
    }
}
```

- [ ] **Step 5: 运行确认通过**

```bash
mvn -q -pl agent test -Dtest=SegmentBufferTest
```
Expected: PASS

- [ ] **Step 6: 实现 EventEmittingToolInterceptor**

`agent/src/main/java/com/javaagent/agent/agent/EventEmittingToolInterceptor.java`：

```java
package com.javaagent.agent.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.javaagent.agent.facade.SegmentBuffer;
import reactor.core.publisher.Sinks;

/**
 * 工具事件直发 + 工具错误兜底（spec §7：失败不中断 ReAct 循环，回传错误文本给模型）。
 */
public class EventEmittingToolInterceptor extends ToolInterceptor {

    private final Sinks.Many<Object> eventSink;
    private final SegmentBuffer buffer;
    private final Long turnId;

    public EventEmittingToolInterceptor(Sinks.Many<Object> eventSink, SegmentBuffer buffer, Long turnId) {
        this.eventSink = eventSink;
        this.buffer = buffer;
        this.turnId = turnId;
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        buffer.recordToolCall(request.getToolCallId(), request.getToolName(), request.getArguments());
        eventSink.tryEmitNext(new com.javaagent.agent.facade.AgentEvent.ToolCall(
            turnId, request.getToolCallId(), request.getToolName(), request.getArguments()));
        long start = System.currentTimeMillis();
        try {
            ToolCallResponse response = handler.call(request);
            long duration = System.currentTimeMillis() - start;
            String result = String.valueOf(response.getResult());
            buffer.recordToolResult(request.getToolCallId(), request.getToolName(), result, true, duration);
            eventSink.tryEmitNext(new com.javaagent.agent.facade.AgentEvent.ToolResult(
                turnId, request.getToolCallId(), request.getToolName(), result, duration, true));
            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String message = "Tool failed: " + e.getMessage();
            buffer.recordToolResult(request.getToolCallId(), request.getToolName(), message, false, duration);
            eventSink.tryEmitNext(new com.javaagent.agent.facade.AgentEvent.ToolResult(
                turnId, request.getToolCallId(), request.getToolName(), message, duration, false));
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), message);
        }
    }

    @Override
    public String getName() {
        return "EventEmittingToolInterceptor";
    }
}
```

注：`ToolCallResponse.getResult()` / `ToolCallRequest.getArguments()` 的确切签名编译期对照 SAA 源码微调（官方文档示例确认 `ToolCallResponse.of(toolCallId, toolName, String)` 存在）。

- [ ] **Step 7: 实现 AgentFacade**

`agent/src/main/java/com/javaagent/agent/facade/AgentFacade.java`：

```java
package com.javaagent.agent.facade;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.javaagent.agent.agent.AgentFactory;
import com.javaagent.agent.agent.EventEmittingToolInterceptor;
import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.Turn;
import com.javaagent.agent.persistence.TurnRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * agent 引擎唯一门面：chat(conversationId, content) → Flux<AgentEvent>。
 * 落库在 agent 模块内完成（web 层零持久化职责）。
 */
@Service
public class AgentFacade {

    private final AgentFactory agentFactory;
    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final String model;

    public AgentFacade(AgentFactory agentFactory, ConversationRepository conversations,
                       TurnRepository turns, MessageRepository messages,
                       org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.options.model:step-3.7-flash}") String model) {
        this.agentFactory = agentFactory;
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.model = model;
    }

    public Flux<AgentEvent> chat(Long conversationId, String content) {
        return Flux.defer(() -> {
            Conversation conv = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));

            int turnSeq = turns.countByConversationId(conv.id()) + 1;
            Turn turn = turns.save(Turn.running(conv.id(), turnSeq));
            messages.save(Message.user(turn.id(), 0, content));

            SegmentBuffer buffer = new SegmentBuffer(turn.id(), messages);
            Sinks.Many<Object> sideEvents = Sinks.many().unicast().onBackpressureBuffer();
            AtomicReference<Object> usageCapture = new AtomicReference<>();

            AgentFactory.AgentHandle handle = agentFactory.create(sideEvents, usageCapture);

            // 工具事件拦截器需要与 handle 里的 agent 绑定 —— AgentFactory.create 增加重载：
            // create(Sinks.Many<Object> sideEvents, AtomicReference<Object> usageCapture,
            //        ToolInterceptor toolInterceptor)
            // EventEmittingToolInterceptor 由 facade 构造后传入（见 Step 8 代码）

            RunnableConfig config = RunnableConfig.builder()
                .threadId(conv.threadId())
                .build();

            Flux<AgentEvent> side = sideEvents.asFlux().map(evt -> {
                if (evt instanceof String thinkingDelta) {
                    buffer.appendThinking(thinkingDelta);
                    return (AgentEvent) new AgentEvent.ThinkingDelta(turn.id(), thinkingDelta);
                }
                return (AgentEvent) evt;  // ToolCall / ToolResult 已由拦截器构造
            });

            Flux<AgentEvent> main = Flux.from(handle.agent().stream(new UserMessage(content), config))
                .concatMap(nodeOutput -> mapNodeOutput(nodeOutput, turn.id(), buffer))
                .onErrorResume(e -> {
                    messages.save(com.javaagent.agent.persistence.Message.error(turn.id(), buffer.currentSeq(), e.toString()));
                    turns.save(turn.fail("ERROR"));
                    return Flux.just(new AgentEvent.TurnError(turn.id(), "AGENT_ERROR", e.getMessage()));
                });

            return Flux.merge(side, main)
                .startWith(new AgentEvent.Meta(turn.id(), conv.id(), model))
                .doFinally(signal -> {
                    sideEvents.tryEmitComplete();
                    buffer.flushAll();
                    Turn current = turns.findById(turn.id()).orElse(turn);
                    if (!"FAILED".equals(current.status())) {
                        AgentEvent.Usage usage = toUsage(usageCapture.get());
                        turns.save(current.complete("STOP", null));
                        conversations.touch(conv.id());
                    }
                });
        });
    }

    /** NodeOutput → 事件：LLM 节点流式 chunk → MessageDelta + SegmentBuffer 累积 */
    private Flux<AgentEvent> mapNodeOutput(com.alibaba.cloud.ai.graph.NodeOutput nodeOutput,
                                           Long turnId, SegmentBuffer buffer) {
        if (nodeOutput instanceof com.alibaba.cloud.ai.graph.streaming.StreamingOutput<?> streaming) {
            String chunk = streaming.chunk();
            if (chunk != null && !chunk.isEmpty()) {
                buffer.appendText(chunk);
                return Flux.just(new AgentEvent.MessageDelta(turnId, chunk));
            }
        }
        return Flux.empty();
    }

    private AgentEvent.Usage toUsage(Object usage) {
        // usage 形态来自 ThinkingTap 捕获的 ChatResponse metadata，编译期按实际类型适配
        if (usage instanceof org.springframework.ai.chat.metadata.Usage u) {
            return new AgentEvent.Usage(
                u.getPromptTokens() == null ? 0 : u.getPromptTokens(),
                u.getCompletionTokens() == null ? 0 : u.getCompletionTokens(),
                u.getTotalTokens() == null ? 0 : u.getTotalTokens());
        }
        return new AgentEvent.Usage(0, 0, 0);
    }
}
```

注：`handle.agent().stream(...)` 与 `StreamingOutput.chunk()` 签名编译期对照 SAA 源码核对；`TurnDone` 事件的发出位置在 doFinally 里改为在 status 判定分支内（见 Step 8 修正）；`SegmentBuffer.currentSeq()` 需补充（返回当前 seq，不递增）：

```java
// SegmentBuffer 追加
public int currentSeq() {
    return seq;
}
```

- [ ] **Step 8: 修正与完善（合并进上面的实现）**

把 doFinally 分支改为：

```java
.doFinally(signal -> {
    sideEvents.tryEmitComplete();
    buffer.flushAll();
    Turn current = turns.findById(turn.id()).orElse(turn);
    if (!"FAILED".equals(current.status())) {
        AgentEvent.Usage usage = toUsage(usageCapture.get());
        turns.save(current.complete("STOP", null));
        conversations.touch(conv.id());
        sideEvents.tryEmitNext(new AgentEvent.TurnDone(turn.id(), "STOP", usage));
    }
})
```

并把 `AgentFactory.create` 扩展为带 ToolInterceptor 的重载：

```java
// AgentFactory 追加
public AgentHandle create(Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture,
                          com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor toolInterceptor) {
    // 与现有 create 相同的装配，但 interceptors(List.of(toolInterceptor))
    // 原双参 create 委托：create(sink, usage, new ToolErrorInterceptor()) 或直接删除双参版本（facade 是唯一调用方）
}
```

同时 facade 的 `doFinally` 需要 `TurnDone` 经合并流发出 —— 由于 sideEvents 在 doFinally 已 complete，改为把 TurnDone 发射放在 main 流的 `concatMap` 之后追加：

```java
Flux<AgentEvent> main = Flux.from(handle.agent().stream(new UserMessage(content), config))
    .concatMap(nodeOutput -> mapNodeOutput(nodeOutput, turn.id(), buffer))
    .concatWith(Flux.defer(() -> {
        buffer.flushAll();
        AgentEvent.Usage usage = toUsage(usageCapture.get());
        turns.save(turns.findById(turn.id()).orElse(turn).complete("STOP", null));
        conversations.touch(conv.id());
        return Flux.just(new AgentEvent.TurnDone(turn.id(), "STOP", usage));
    }))
    .onErrorResume(e -> {
        buffer.flushAll();
        messages.save(Message.error(turn.id(), buffer.currentSeq(), e.toString()));
        turns.save(turn.fail("ERROR"));
        return Flux.just(new AgentEvent.TurnError(turn.id(), "AGENT_ERROR", e.getMessage()));
    });
```

doFinally 只负责：`sideEvents.tryEmitComplete()`（兜底）。

- [ ] **Step 9: 写失败测试（AgentFacade，打桩 AgentFactory）**

`agent/src/test/java/com/javaagent/agent/facade/AgentFacadeTest.java`：

```java
package com.javaagent.agent.facade;

import com.javaagent.agent.agent.AgentFactory;
import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.Turn;
import com.javaagent.agent.persistence.TurnRepository;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentFacadeTest {

    private AgentFacade facade;
    private InMemoryConversationRepository conversations;
    private InMemoryTurnRepository turns;
    private InMemoryMessageRepository messages;

    @BeforeEach
    void setUp() {
        conversations = new InMemoryConversationRepository();
        turns = new InMemoryTurnRepository();
        messages = new InMemoryMessageRepository();

        AgentFactory factory = mock(AgentFactory.class);
        when(factory.create(any(), any(), any())).thenAnswer(inv -> new AgentFactory.AgentHandle(
            new StubAgent(), null));

        facade = new AgentFacade(factory, conversations, turns, messages, "step-3.7-flash");
    }

    @Test
    void chatEmitsMetaDeltaTurnDoneAndPersistsCompleteMessages() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", Instant.now(), Instant.now()));

        StepVerifier.create(facade.chat(conv.id(), "你好"))
            .expectNextMatches(e -> e instanceof AgentEvent.Meta m && m.model().equals("step-3.7-flash"))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("回"))
            .expectNextMatches(e -> e instanceof AgentEvent.MessageDelta d && d.content().equals("答"))
            .expectNextMatches(e -> e instanceof AgentEvent.TurnDone)
            .verifyComplete();

        Optional<Turn> turn = turns.findTopByConversationIdOrderBySeqDesc(conv.id());
        assertThat(turn).isPresent();
        assertThat(turn.get().status()).isEqualTo("COMPLETED");

        List<Message> saved = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(saved).extracting(Message::msgType).containsExactly("USER", "TEXT");
        assertThat(saved.get(1).content()).isEqualTo("回答");
    }

    @Test
    void chatPersistsUserMessageWithSeqZero() {
        Conversation conv = conversations.save(
            new Conversation(null, "t", "conv-1", Instant.now(), Instant.now()));
        facade.chat(conv.id(), "问题").blockLast();

        assertThat(messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id()).get(0).seq()).isEqualTo(0);
    }

    /** 桩：agent.stream 返回两个文本 chunk */
    static class StubAgent {
        public Flux<com.alibaba.cloud.ai.graph.NodeOutput> stream(Object input, RunnableConfig config) {
            // StreamingOutput 具体构造方式编译期对照 SAA；此处语义为两个 LLM 文本 chunk
            return Flux.just(
                stubStreamingOutput("回"),
                stubStreamingOutput("答"));
        }

        private com.alibaba.cloud.ai.graph.NodeOutput stubStreamingOutput(String chunk) {
            // 若 StreamingOutput 构造不可用，反射或改为 map 返回带 chunk() 的实现
            // 编译期按 SAA 1.2.3 实际 API 调整 —— 语义不变：chunk() 返回该字符串
            throw new UnsupportedOperationException("编译期按 SAA API 实现桩");
        }
    }

    // InMemory 仓储实现（省略 CRUD 样板，覆盖测试用到的方法）
    static class InMemoryConversationRepository implements ConversationRepository {
        long nextId = 1;
        final List<Conversation> data = new ArrayList<>();
        @Override public <S extends Conversation> S save(S e) {
            if (e.id() == null) { e = new Conversation(nextId, e.title(), e.threadId(), e.createdAt(), e.updatedAt()); nextId++; }
            data.removeIf(c -> c.id().equals(e.id())); data.add(e); return e;
        }
        @Override public Optional<Conversation> findById(Long id) { return data.stream().filter(c -> c.id().equals(id)).findFirst(); }
        @Override public List<Conversation> findAllByOrderByUpdatedAtDesc() { return data; }
        @Override public void touch(Long id) {}
        // 其余 CRUD 方法与 SegmentBufferTest 中的 RecordingRepo 同模式省略
    }

    static class InMemoryTurnRepository implements TurnRepository {
        long nextId = 1;
        final List<Turn> data = new ArrayList<>();
        @Override public <S extends Turn> S save(S e) {
            if (e.id() == null) { e = new Turn(nextId, e.conversationId(), e.seq(), e.status(), e.finishReason(), e.usage(), e.startedAt(), e.finishedAt()); nextId++; }
            data.removeIf(t -> t.id().equals(e.id())); data.add(e); return e;
        }
        @Override public int countByConversationId(Long conversationId) { return (int) data.stream().filter(t -> t.conversationId().equals(conversationId)).count(); }
        @Override public Optional<Turn> findTopByConversationIdOrderBySeqDesc(Long conversationId) {
            return data.stream().filter(t -> t.conversationId().equals(conversationId)).max(java.util.Comparator.comparing(Turn::seq));
        }
        @Override public Optional<Turn> findById(Long id) { return data.stream().filter(t -> t.id().equals(id)).findFirst(); }
        // 其余 CRUD 省略
    }

    static class InMemoryMessageRepository implements MessageRepository {
        long nextId = 1;
        final List<Message> data = new ArrayList<>();
        @Override public <S extends Message> S save(S e) {
            if (e.id() == null) { e = new Message(nextId++, e.turnId(), e.seq(), e.msgType(), e.content(), e.callId(), e.toolName(), e.arguments(), e.result(), e.success(), e.durationMs(), e.createdAt()); }
            data.removeIf(m -> m.id().equals(e.id())); data.add(e); return e;
        }
        @Override public List<Message> findByTurnIdOrderBySeq(Long turnId) {
            return data.stream().filter(m -> turnId.equals(m.turnId())).sorted(java.util.Comparator.comparing(Message::seq)).toList();
        }
        @Override public List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId) { return data.stream().toList(); }
        // 其余 CRUD 省略
    }
}
```

注：Mockito 打桩 `AgentFactory`（final 类/record 需 `mockito-inline`，Spring Boot 3.5 测试默认启用 inline mock maker，可用）。InMemory 仓储省略方法按接口补齐实现（与 RecordingRepo 模式一致）。

- [ ] **Step 10: 运行确认通过**

```bash
mvn -q -pl agent test -Dtest=AgentFacadeTest -DexcludedGroups=manual
```
Expected: PASS

- [ ] **Step 11: Commit**

```bash
git add agent/src
git commit -m "feat: AgentFacade 事件流核心（thinking 旁路/工具拦截/段落落库/轮次状态机）"
```

---

### Task 9: web 接口层（REST + SSE + SseEventMapper）

**Files:**
- Create: `web/src/main/java/com/javaagent/web/dto/CreateConversationRequest.java`
- Create: `web/src/main/java/com/javaagent/web/dto/ConversationResponse.java`
- Create: `web/src/main/java/com/javaagent/web/dto/ChatRequest.java`
- Create: `web/src/main/java/com/javaagent/web/dto/TurnResponse.java`
- Create: `web/src/main/java/com/javaagent/web/controller/ConversationController.java`
- Create: `web/src/main/java/com/javaagent/web/controller/ChatController.java`
- Create: `web/src/main/java/com/javaagent/web/stream/SseEventMapper.java`
- Test: `web/src/test/java/com/javaagent/web/controller/ConversationControllerTest.java`
- Test: `web/src/test/java/com/javaagent/web/controller/ChatControllerSseTest.java`
- Modify: `web/src/main/resources/application.yml`（Flyway 指向 agent classpath 的迁移目录，见 Task 7 注：迁移文件在 agent 模块，web 启动执行）

**Interfaces:**
- Consumes: `AgentFacade.chat(Long, String): Flux<AgentEvent>`；三个 Repository
- Produces（对外 HTTP 契约，前端 Task 12 依赖）:

| 方法 | 路径 | 请求/响应 |
|---|---|---|
| POST | `/api/conversations` | `{title?}` → `{"id":1,"title":"...","createdAt":"..."}` |
| GET | `/api/conversations` | → `[{"id":1,"title":"...","turnCount":3,"updatedAt":"..."}]` |
| GET | `/api/conversations/{id}/turns` | → `[{"id":1,"seq":1,"status":"COMPLETED","messages":[{"seq":0,"msgType":"USER","content":"..."} , ...]}]` |
| DELETE | `/api/conversations/{id}` | → 204 |
| POST | `/api/conversations/{id}/chat` | `{content}` → SSE 流（事件名/载荷见 SseEventMapper） |

SSE 事件（`event:` + JSON `data:`，均带 `turnId`、`seq`）：
`meta{turnId,conversationId,model}`、`thinking_delta{content}`、`message_delta{content}`、`tool_call{callId,toolName,arguments}`、`tool_result{callId,toolName,result,durationMs,success}`、`turn_done{finishReason,usage{promptTokens,completionTokens,totalTokens}}`、`error{code,message}`

- [ ] **Step 1: DTO**

`web/src/main/java/com/javaagent/web/dto/CreateConversationRequest.java`：

```java
package com.javaagent.web.dto;

public record CreateConversationRequest(String title) {
}
```

`web/src/main/java/com/javaagent/web/dto/ChatRequest.java`：

```java
package com.javaagent.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String content) {
}
```

`web/src/main/java/com/javaagent/web/dto/ConversationResponse.java`：

```java
package com.javaagent.web.dto;

import com.javaagent.agent.persistence.Conversation;

public record ConversationResponse(Long id, String title, int turnCount, String updatedAt) {
}
```

`web/src/main/java/com/javaagent/web/dto/TurnResponse.java`：

```java
package com.javaagent.web.dto;

import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.Turn;

import java.util.List;

public record TurnResponse(Long id, int seq, String status, String finishReason,
                           List<MessageItem> messages) {

    public record MessageItem(int seq, String msgType, String content, String callId,
                              String toolName, String arguments, String result,
                              Boolean success, Long durationMs) {
    }

    public static TurnResponse from(Turn turn, List<Message> messages) {
        return new TurnResponse(turn.id(), turn.seq(), turn.status(), turn.finishReason(),
            messages.stream().map(m -> new MessageItem(m.seq(), m.msgType(), m.content(),
                m.callId(), m.toolName(), m.arguments(), m.result(), m.success(), m.durationMs())).toList());
    }
}
```

- [ ] **Step 2: 写失败测试（ConversationController）**

`web/src/test/java/com/javaagent/web/controller/ConversationControllerTest.java`：

```java
package com.javaagent.web.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.javaagent.agent.facade.AgentFacade;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.TurnRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConversationController.class)
class ConversationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;

    @Test
    void createReturnsConversation() throws Exception {
        when(conversations.save(any())).thenReturn(
            new com.javaagent.agent.persistence.Conversation(1L, "新会话", "conv-1",
                java.time.Instant.now(), java.time.Instant.now()));

        mockMvc.perform(post("/api/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新会话\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.title").value("新会话"));
    }

    @Test
    void listReturnsConversationsWithTurnCount() throws Exception {
        when(conversations.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(
            new com.javaagent.agent.persistence.Conversation(1L, "会话A", "conv-1",
                java.time.Instant.now(), java.time.Instant.now())));
        when(turns.countByConversationId(1L)).thenReturn(3);

        mockMvc.perform(get("/api/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].turnCount").value(3));
    }

    @Test
    void turnsReplayReturnsNestedMessages() throws Exception {
        when(conversations.findById(1L)).thenReturn(java.util.Optional.of(
            new com.javaagent.agent.persistence.Conversation(1L, "会话A", "conv-1",
                java.time.Instant.now(), java.time.Instant.now())));
        when(turns.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of(
            new com.javaagent.agent.persistence.Turn(10L, 1L, 1, "COMPLETED", "STOP", null,
                java.time.Instant.now(), java.time.Instant.now())));
        when(messages.findByTurnIdOrderBySeq(10L)).thenReturn(List.of(
            new com.javaagent.agent.persistence.Message(1L, 10L, 0, "USER", "问题", null, null, null, null, null, null, java.time.Instant.now()),
            new com.javaagent.agent.persistence.Message(2L, 10L, 1, "TEXT", "回答", null, null, null, null, null, null, java.time.Instant.now())));

        mockMvc.perform(get("/api/conversations/1/turns"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].messages[0].msgType").value("USER"))
            .andExpect(jsonPath("$[0].messages[1].msgType").value("TEXT"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/conversations/1"))
            .andExpect(status().isNoContent());
    }
}
```

注：测试引用 `turns.findByConversationIdOrderBySeqAsc(Long)` —— `TurnRepository` 需补该方法（`List<Turn> findByConversationIdOrderBySeqAsc(Long conversationId)`）。DELETE 需要级联删 turn/message/checkpoint：PG 有 `ON DELETE CASCADE`，`conversations.deleteById(id)` 即可（checkpoint 表由 Task 10 处理，此处仅业务表）。

- [ ] **Step 3: 运行确认失败**

```bash
mvn -q -pl web test -Dtest=ConversationControllerTest
```
Expected: FAIL（Controller 不存在）

- [ ] **Step 4: 实现 ConversationController**

`web/src/main/java/com/javaagent/web/controller/ConversationController.java`：

```java
package com.javaagent.web.controller;

import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.TurnRepository;
import com.javaagent.web.dto.ConversationResponse;
import com.javaagent.web.dto.CreateConversationRequest;
import com.javaagent.web.dto.TurnResponse;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;

    public ConversationController(ConversationRepository conversations,
                                  TurnRepository turns, MessageRepository messages) {
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
    }

    @PostMapping
    public ConversationResponse create(@RequestBody(required = false) CreateConversationRequest request) {
        String title = (request == null || request.title() == null || request.title().isBlank())
            ? "新会话" : request.title();
        Conversation saved = conversations.save(new Conversation(null, title,
            "conv-" + System.nanoTime(), Instant.now(), Instant.now()));
        return new ConversationResponse(saved.id(), saved.title(), 0, saved.updatedAt().toString());
    }

    @GetMapping
    public List<ConversationResponse> list() {
        return conversations.findAllByOrderByUpdatedAtDesc().stream()
            .map(c -> new ConversationResponse(c.id(), c.title(),
                turns.countByConversationId(c.id()), c.updatedAt().toString()))
            .toList();
    }

    @GetMapping("/{id}/turns")
    public List<TurnResponse> turns(@PathVariable Long id) {
        if (conversations.findById(id).isEmpty()) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        return turns.findByConversationIdOrderBySeqAsc(id).stream()
            .map(t -> TurnResponse.from(t, messages.findByTurnIdOrderBySeq(t.id())))
            .toList();
    }

    @DeleteMapping("/{id}")
    @org.springframework.http.HttpStatus
    public void delete(@PathVariable Long id) {
        conversations.findById(id).ifPresent(c -> conversations.deleteById(id));
    }
}
```

注：DELETE 方法签名去掉 `@org.springframework.http.HttpStatus`（误写），用 `@ResponseStatus(HttpStatus.NO_CONTENT)`。

- [ ] **Step 5: 运行确认通过**

```bash
mvn -q -pl web test -Dtest=ConversationControllerTest
```
Expected: PASS

- [ ] **Step 6: 写失败测试（ChatController SSE）**

`web/src/test/java/com/javaagent/web/controller/ChatControllerSseTest.java`：

```java
package com.javaagent.web.controller;

import com.javaagent.agent.facade.AgentEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import com.javaagent.agent.facade.AgentFacade;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.TurnRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
class ChatControllerSseTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;

    @Test
    void chatStreamsSseEventsInOrder() {
        when(agentFacade.chat(eq(1L), eq("你好"))).thenReturn(Flux.just(
            new AgentEvent.Meta(10L, 1L, "step-3.7-flash"),
            new AgentEvent.ThinkingDelta(10L, "思考"),
            new AgentEvent.MessageDelta(10L, "回"),
            new AgentEvent.ToolCall(10L, "c1", "list_dir", "{\"path\":\".\"}"),
            new AgentEvent.ToolResult(10L, "c1", "list_dir", "a.txt", 120L, true),
            new AgentEvent.MessageDelta(10L, "答"),
            new AgentEvent.TurnDone(10L, "STOP", new AgentEvent.Usage(10, 5, 15))));

        webTestClient.post().uri("/api/conversations/1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new com.javaagent.web.dto.ChatRequest("你好"))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class).value(body -> {
                // 断言事件名顺序与关键字段
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("event:meta").contains("\"turnId\":10")
                    .contains("event:thinking_delta").contains("思考")
                    .contains("event:message_delta").contains("回")
                    .contains("event:tool_call").contains("list_dir")
                    .contains("event:tool_result").contains("\"success\":true")
                    .contains("event:turn_done").contains("\"totalTokens\":15");
            });
    }

    @Test
    void blankContentIsRejected() {
        webTestClient.post().uri("/api/conversations/1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new com.javaagent.web.dto.ChatRequest(" "))
            .exchange()
            .expectStatus().isBadRequest();
    }
}
```

注：`@SpringBootTest` 需要 DataSource —— 加 Testcontainers `@ServiceConnection` PostgreSQLContainer（同 FlywayMigrationTest 模式），或改用 `@WebMvcTest` + WebTestClient 绑定 MockMvc（无 DB）。推荐后者：`@WebMvcTest(ChatController.class)` + `@AutoConfigureWebTestClient`。

- [ ] **Step 7: 实现 SseEventMapper 与 ChatController**

`web/src/main/java/com/javaagent/web/stream/SseEventMapper.java`：

```java
package com.javaagent.web.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.agent.facade.AgentEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AgentEvent → SSE（事件名 + JSON data）。seq 由流内递增序号承担。
 */
@Component
public class SseEventMapper {

    private final ObjectMapper objectMapper;

    public SseEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> toSse(Flux<AgentEvent> events) {
        AtomicLong seq = new AtomicLong();
        return events.map(evt -> toSse(evt, seq.incrementAndGet()));
    }

    ServerSentEvent<String> toSse(AgentEvent evt, long seq) {
        return ServerSentEvent.builder(toJson(payload(evt, seq)))
            .event(eventName(evt))
            .build();
    }

    private String eventName(AgentEvent evt) {
        return switch (evt) {
            case AgentEvent.Meta m -> "meta";
            case AgentEvent.ThinkingDelta t -> "thinking_delta";
            case AgentEvent.MessageDelta m -> "message_delta";
            case AgentEvent.ToolCall t -> "tool_call";
            case AgentEvent.ToolResult t -> "tool_result";
            case AgentEvent.TurnDone t -> "turn_done";
            case AgentEvent.TurnError t -> "error";
        };
    }

    private Map<String, Object> payload(AgentEvent evt, long seq) {
        return switch (evt) {
            case AgentEvent.Meta m -> Map.of("turnId", m.turnId(), "conversationId", m.conversationId(),
                "model", m.model(), "seq", seq);
            case AgentEvent.ThinkingDelta t -> Map.of("turnId", t.turnId(), "content", t.content(), "seq", seq);
            case AgentEvent.MessageDelta m -> Map.of("turnId", m.turnId(), "content", m.content(), "seq", seq);
            case AgentEvent.ToolCall t -> Map.of("turnId", t.turnId(), "callId", t.callId(),
                "toolName", t.toolName(), "arguments", t.arguments(), "seq", seq);
            case AgentEvent.ToolResult t -> Map.of("turnId", t.turnId(), "callId", t.callId(),
                "toolName", t.toolName(), "result", t.result(),
                "durationMs", t.durationMs(), "success", t.success(), "seq", seq);
            case AgentEvent.TurnDone t -> Map.of("turnId", t.turnId(), "finishReason", t.finishReason(),
                "usage", t.usage(), "seq", seq);
            case AgentEvent.TurnError e -> Map.of("turnId", e.turnId(), "code", e.code(),
                "message", e.message(), "seq", seq);
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"序列化失败\"}";
        }
    }
}
```

`web/src/main/java/com/javaagent/web/controller/ChatController.java`：

```java
package com.javaagent.web.controller;

import com.javaagent.agent.facade.AgentFacade;
import com.javaagent.web.dto.ChatRequest;
import com.javaagent.web.stream.SseEventMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final AgentFacade agentFacade;
    private final SseEventMapper sseEventMapper;

    public ChatController(AgentFacade agentFacade, SseEventMapper sseEventMapper) {
        this.agentFacade = agentFacade;
        this.sseEventMapper = sseEventMapper;
    }

    @PostMapping(value = "/api/conversations/{conversationId}/chat",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable Long conversationId,
                                              @Valid @RequestBody ChatRequest request) {
        return sseEventMapper.toSse(agentFacade.chat(conversationId, request.content()));
    }
}
```

`web/src/main/resources/application.yml` 追加（jakarta validation 依赖在 starter-web 中默认不含，web pom 加 `spring-boot-starter-validation`）：

```yaml
# 无新增键；仅确认 spring.ai.openai 配置已在 Task 1 就位
```

web/pom.xml dependencies 追加：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

- [ ] **Step 8: 运行确认通过 + 全模块回归**

```bash
mvn -q test -DexcludedGroups=manual
```
Expected: PASS（agent + web 全部测试）

- [ ] **Step 9: Commit**

```bash
git add web/src web/pom.xml
git commit -m "feat: REST 会话接口与 SSE 流式对话接口（事件协议 7 类）"
```

---

### Task 10: PG checkpoint saver（会话记忆持久化）

**Files:**
- Modify: `agent/pom.xml`（加 spring-ai-alibaba-starter-memory-jdbc）
- Modify: `agent/src/main/java/com/javaagent/agent/config/AgentBeansConfig.java`（MemorySaver → PG saver）
- Test: `agent/src/test/java/com/javaagent/agent/persistence/CheckpointRestartTest.java`

**Interfaces:**
- Consumes: SAA `spring-ai-alibaba-starter-memory-jdbc` 提供的 JDBC saver 类（类名编译期从 jar 确认：`mvn -pl agent dependency:sources` 后在 `com.alibaba.cloud.ai.graph.checkpoint.savers` 包下找 JDBC/Postgres 实现；若该 starter 不存在此类，替代路径见 Step 4 注）
- Produces: `AgentBeansConfig.checkpointSaver`（PG 实现，threadId=conversation.threadId 跨轮/跨进程恢复记忆）

- [ ] **Step 1: 加依赖并核对 saver 类**

```bash
cd /Users/newbeefly/Coder/Project/Claude/javaAgent
# agent/pom.xml dependencies 追加：
#   <dependency><groupId>com.alibaba.cloud.ai</groupId>
#     <artifactId>spring-ai-alibaba-starter-memory-jdbc</artifactId></dependency>
mvn -q -pl agent dependency:resolve
mvn -pl agent dependency:sources -q
find ~/.m2/repository/com/alibaba/cloud/ai -name "*memory-jdbc*.jar" | head -3
# 解压确认 saver 类名：
JAR=$(find ~/.m2/repository/com/alibaba/cloud/ai -name "spring-ai-alibaba-starter-memory-jdbc-*.jar" ! -name "*sources*" | head -1)
unzip -l "$JAR" | grep -i saver
```
Expected: 输出包含 JDBC/Postgres 相关 saver 类名（记下来用于 Step 2）

- [ ] **Step 2: 写失败测试（跨 agent 实例的记忆恢复）**

`agent/src/test/java/com/javaagent/agent/persistence/CheckpointRestartTest.java`：

```java
package com.javaagent.agent.persistence;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.javaagent.agent.agent.AgentFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用打桩 ChatModel 验证：两个独立 ReactAgent 实例共享同一 PG saver + 同一 threadId 时，
 * 第二个实例能看到第一个实例写入的消息历史（模拟应用重启）。
 */
@SpringBootTest
@Testcontainers
class CheckpointRestartTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    void memorySurvivesAcrossAgentInstances() {
        // 实现方式（编译期按 saver API 调整）：
        // 1. saver = PG saver bean（测试内直接 new，传入 JdbcTemplate/DataSource）
        // 2. agent1 = ReactAgent.builder().model(stubModel).saver(saver).build()
        //    agent1.call("记住暗号是苹果", config(threadId="t1"))
        // 3. agent2 = 另一个 ReactAgent 实例（同 saver，同 threadId）
        //    断言 agent2 执行时收到的消息历史包含"记住暗号是苹果"
        //    （stubModel 记录收到的 Prompt 消息列表即可断言）
        assertThat(true).isTrue();  // 占位断言：真实断言见上面注释实现
    }
}
```

注：本测试是行为级验证，具体断言代码依赖 saver API 形态（Step 1 核对后补全）。stub ChatModel 复用 `ThinkingTapChatModelTest.ChatModelStub`（call 记录 prompt）。**若 SAA 1.1.2.3 的 memory-jdbc starter 无 JDBC saver**：替代路径 = 自实现 `BaseCheckpointSaver`（PG 表 + JSONB 序列化 checkpoint，参考 MemorySaver 源码结构，表结构加 V2__checkpoint.sql）——此时本任务拆为"自实现 saver"子步骤，验收标准不变（跨实例记忆恢复）。

- [ ] **Step 3: 替换 Bean**

`AgentBeansConfig.checkpointSaver()` 修改为（类名以 Step 1 结果为准，示例假设 `PostgresSaver`）：

```java
@Bean
public BaseCheckpointSaver checkpointSaver(javax.sql.DataSource dataSource) {
    // 类名/构造签名以 Step 1 解压结果为准；若需要建表，加 V2__checkpoint.sql
    return new PostgresSaver(new JdbcTemplate(dataSource));
}
```

同时 `AgentFactory` 的 `checkpointSaver` 字段类型从 `Object` 收紧为 `BaseCheckpointSaver`。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q -pl agent test -Dtest=CheckpointRestartTest
```
Expected: PASS（跨实例记忆恢复断言通过）

- [ ] **Step 5: Commit**

```bash
git add agent/pom.xml agent/src
git commit -m "feat: PG checkpoint saver 持久化会话记忆"
```

---

### Task 11: 消息压缩（CompactionService）

**Files:**
- Create: `agent/src/main/java/com/javaagent/agent/compaction/CompactionService.java`
- Create: `agent/src/main/resources/db/migration/V2__compaction.sql`（若 summary 落库需要；不加表则不需要迁移）
- Test: `agent/src/test/java/com/javaagent/agent/compaction/CompactionServiceTest.java`
- Modify: `agent/src/main/java/com/javaagent/agent/facade/AgentFacade.java`（chat 前触发压缩检查）

**Interfaces:**
- Consumes: `MessageRepository.findByConversationIdOrderByTurnIdAscSeqAsc`、`ChatModel`（摘要调用）
- Produces: `CompactionService.compactIfNeeded(Long conversationId): Optional<String>`（返回新生成的 threadId，无压缩返回 empty）
  - 阈值：`agent.compaction.threshold-tokens`（默认 24000）
  - 策略：估算 token（字符数/4）；超阈值时把较早 turn 的 THINKING/TOOL/TEXT 消息拼成摘要请求，LLM 产出 summary 文本；**给模型的记忆**切换到新 threadId（`conv-{id}-v{n}`），并把 compacted 历史作为该线程首轮输入重建记忆；**给前端的存储**（turn/message）不动
  - Summary 以 `SUMMARY` 类型 message 存在新 threadId 的引导轮次（turn seq=0，status=COMPLETED，不对外部回放接口暴露——回放接口按 conversation 关联，引导轮 conversationId 置 NULL）

- [ ] **Step 1: 写失败测试**

`agent/src/test/java/com/javaagent/agent/compaction/CompactionServiceTest.java`：

```java
package com.javaagent.agent.compaction;

import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.Turn;
import com.javaagent.agent.persistence.TurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompactionServiceTest {

    private ConversationRepository conversations;
    private TurnRepository turns;
    private MessageRepository messages;
    private org.springframework.ai.chat.model.ChatModel chatModel;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        turns = mock(TurnRepository.class);
        messages = mock(MessageRepository.class);
        chatModel = mock(org.springframework.ai.chat.model.ChatModel.class);
    }

    @Test
    void underThresholdReturnsEmpty() {
        when(conversations.findById(1L)).thenReturn(Optional.of(
            new Conversation(1L, "t", "conv-1", Instant.now(), Instant.now())));
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(
            List.of(new Message(1L, 1L, 0, "USER", "短对话", null, null, null, null, null, null, Instant.now())));

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        assertThat(service.compactIfNeeded(1L)).isEmpty();
    }

    @Test
    void overThresholdSummarizesAndReturnsNewThreadId() {
        Conversation conv = new Conversation(1L, "t", "conv-1", Instant.now(), Instant.now());
        when(conversations.findById(1L)).thenReturn(Optional.of(conv));

        // 构造超阈值历史：一段 ~100k 字符的消息
        String big = "x".repeat(100_000);
        when(messages.findByConversationIdOrderByTurnIdAscSeqAsc(1L)).thenReturn(List.of(
            new Message(1L, 1L, 0, "USER", big, null, null, null, null, null, null, Instant.now()),
            new Message(2L, 1L, 1, "TEXT", big, null, null, null, null, null, null, Instant.now())));

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
            new org.springframework.ai.chat.model.ChatResponse(List.of(
                new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("这是历史摘要")))));
        when(conversations.save(any())).thenReturn(conv);
        when(turns.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompactionService service = new CompactionService(conversations, turns, messages, chatModel, 24000);

        Optional<String> newThreadId = service.compactIfNeeded(1L);

        assertThat(newThreadId).contains("conv-1-v1");
        // 摘要请求里包含原始历史内容
        Mockito.verify(chatModel).call(org.mockito.ArgumentMatchers.argThat(
            (org.springframework.ai.chat.prompt.Prompt p) -> p.getContents().get(0).getText().contains(big.substring(0, 100))));
    }

    @Test
    void summaryMessageIsPersistedForNewThread() {
        // 同上场景，验证 messages.save 被调用且 msgType 为 SUMMARY
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -pl agent test -Dtest=CompactionServiceTest
```
Expected: FAIL

- [ ] **Step 3: 实现 CompactionService**

`agent/src/main/java/com/javaagent/agent/compaction/CompactionService.java`：

```java
package com.javaagent.agent.compaction;

import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.Turn;
import com.javaagent.agent.persistence.TurnRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 历史超阈值时压缩"给模型的记忆"（新 threadId + 摘要引导），展示存储不动（spec §6）。
 */
@Service
public class CompactionService {

    private static final String SUMMARY_SYSTEM_PROMPT = """
        你是对话历史压缩器。把给定的多轮对话（含思考与工具调用记录）压缩为一段
        忠实、信息完备的摘要：保留用户目标、已做过的关键操作与结论、未决事项。
        直接输出摘要正文，不要任何前后缀。
        """;

    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final ChatModel chatModel;
    private final int thresholdTokens;

    public CompactionService(ConversationRepository conversations, TurnRepository turns,
                             MessageRepository messages, ChatModel chatModel,
                             @Value("${agent.compaction.threshold-tokens:24000}") int thresholdTokens) {
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.chatModel = chatModel;
        this.thresholdTokens = thresholdTokens;
    }

    public Optional<String> compactIfNeeded(Long conversationId) {
        Conversation conv = conversations.findById(conversationId).orElse(null);
        if (conv == null) {
            return Optional.empty();
        }
        List<Message> history = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conversationId);
        long estimatedTokens = history.stream()
            .mapToLong(m -> m.content() == null ? 0 : m.content().length() / 4
                + (m.result() == null ? 0 : m.result().length() / 4))
            .sum();
        if (estimatedTokens <= thresholdTokens) {
            return Optional.empty();
        }

        String transcript = renderTranscript(history);
        String summary = chatModel.call(new Prompt(List.of(
            new SystemMessage(SUMMARY_SYSTEM_PROMPT),
            new UserMessage(transcript)))).getResult().getOutput().getText();

        String currentThread = conv.threadId();
        int version = parseVersion(currentThread, conversationId);
        String newThreadId = "conv-" + conversationId + "-v" + (version + 1);

        // 引导轮：conversation_id 为 NULL（不进回放接口），SUMMARY 消息
        Turn bootstrap = turns.save(new Turn(null, null, 0, "COMPLETED", "SUMMARY",
            null, Instant.now(), Instant.now()));
        messages.save(new Message(null, bootstrap.id(), 0, "SUMMARY", summary,
            null, null, null, null, null, null, Instant.now()));

        conversations.save(new Conversation(conv.id(), conv.title(), newThreadId,
            conv.createdAt(), Instant.now()));
        return Optional.of(newThreadId);
    }

    private String renderTranscript(List<Message> history) {
        StringBuilder sb = new StringBuilder("以下是完整对话历史：\n\n");
        for (Message m : history) {
            String body = switch (m.msgType()) {
                case "USER" -> "用户: " + m.content();
                case "THINKING" -> "(思考) " + m.content();
                case "TEXT" -> "助手: " + m.content();
                case "TOOL_CALL" -> "助手调用工具 " + m.toolName() + " 入参 " + m.arguments();
                case "TOOL_RESULT" -> "工具 " + m.toolName() + " 返回 " + truncate(m.result());
                default -> "";
            };
            if (!body.isEmpty()) {
                sb.append(body).append('\n');
            }
        }
        return sb.toString();
    }

    private int parseVersion(String threadId, Long conversationId) {
        String prefix = "conv-" + conversationId + "-v";
        if (threadId != null && threadId.startsWith(prefix)) {
            try {
                return Integer.parseInt(threadId.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 2000 ? s.substring(0, 2000) + "...[截断]" : s;
    }
}
```

注：引导轮 `conversation_id` 为 NULL 与 V1 DDL 的 `NOT NULL` 冲突 —— 决策：给 turn 加 `conversation_id` 可空需要改 DDL。更简单的做法：**summary 不落 turn/message 表**，`CompactionService` 把摘要存到 `conversation` 新增列 `compact_summary TEXT`（V2__compaction.sql：`ALTER TABLE conversation ADD COLUMN compact_summary TEXT;`），新 threadId 的记忆重建由 AgentFacade 在压缩后首轮把 summary 作为 SystemMessage 注入。采用这个做法：删除引导轮逻辑，V2 迁移 + conversation record 加字段。测试同步调整为断言 `conversations.save` 的实体带 `compactSummary`。

- [ ] **Step 4: AgentFacade 接入压缩检查**

`AgentFacade.chat(...)` 的 `Flux.defer` 开头（读取 conversation 之后）加：

```java
compactionService.compactIfNeeded(conversationId);  // 返回值不必使用：threadId 已刷新在 conversation 上
Conversation conv = conversations.findById(conversationId).orElseThrow(...);  // 重新读取（压缩后 threadId 已更新）
```

注入 `CompactionService` 构造参数（打桩的 `AgentFacadeTest` 同步补 mock）。

- [ ] **Step 5: 运行确认通过**

```bash
mvn -q -pl agent test -Dtest=CompactionServiceTest -Dtest+=AgentFacadeTest
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src
git commit -m "feat: 消息压缩（阈值触发 LLM 摘要 + 新 threadId 记忆切换，展示存储不动）"
```

---

### Task 12: Vue3 前端（SSE 解析 + 聊天界面）

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`
- Create: `frontend/src/main.ts`, `frontend/src/App.vue`
- Create: `frontend/src/types.ts`, `frontend/src/api/sse.ts`, `frontend/src/api/rest.ts`
- Create: `frontend/src/components/ThinkingBlock.vue`, `frontend/src/components/ToolCard.vue`, `frontend/src/components/MessageBubble.vue`
- Test: `frontend/src/api/__tests__/sse.spec.ts`（Vitest）

**Interfaces:**
- Consumes: Task 9 的 REST + SSE 契约
- Produces: 可 `npm run dev` 启动、代理 `/api` 到 `localhost:8080` 的聊天界面

- [ ] **Step 1: 脚手架与配置**

```bash
cd /Users/newbeefly/Coder/Project/Claude/javaAgent
npm create vite@latest frontend -- --template vue-ts
cd frontend
npm install
npm install -D vitest
```

`frontend/vite.config.ts`：

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
})
```

`frontend/src/types.ts`：

```ts
export interface SseEvent {
  event: string
  data: any
}

export interface TurnRecord {
  id: number
  seq: number
  status: string
  finishReason?: string
  messages: MessageRecord[]
}

export interface MessageRecord {
  seq: number
  msgType: 'USER' | 'THINKING' | 'TEXT' | 'TOOL_CALL' | 'TOOL_RESULT' | 'ERROR' | 'SUMMARY'
  content?: string
  callId?: string
  toolName?: string
  arguments?: string
  result?: string
  success?: boolean
  durationMs?: number
}

export interface ChatTurn {
  userText: string
  thinking: string
  text: string
  tools: ToolEvent[]
  status: 'streaming' | 'done' | 'error'
}

export interface ToolEvent {
  callId: string
  toolName: string
  arguments?: string
  result?: string
  success?: boolean
  durationMs?: number
}
```

- [ ] **Step 2: 写失败测试（SSE 解析器）**

`frontend/src/api/__tests__/sse.spec.ts`：

```ts
import { describe, it, expect, vi } from 'vitest'
import { streamSse, parseSseBlock } from '../sse'

describe('parseSseBlock', () => {
  it('parses event and json data', () => {
    const ev = parseSseBlock('event:tool_call\ndata:{"callId":"c1"}')
    expect(ev.event).toBe('tool_call')
    expect(ev.data.callId).toBe('c1')
  })

  it('handles multi-line data', () => {
    const ev = parseSseBlock('event:message_delta\ndata:{"content":\ndata:"你好"}')
    expect(ev.data.content).toBe('你好')
  })

  it('ignores comment and empty blocks', () => {
    expect(parseSseBlock(':keepalive')).toBeNull()
    expect(parseSseBlock('')).toBeNull()
  })
})

describe('streamSse chunking', () => {
  it('handles split chunks and half lines', async () => {
    const chunks = [
      'event:meta\ndata:{"turnId":1}\n\nevent:message_de',
      'lta\ndata:{"content":"A"}\n\n',
    ]
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: new ReadableStream({
        start(controller) {
          const enc = new TextEncoder()
          for (const c of chunks) controller.enqueue(enc.encode(c))
          controller.close()
        },
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const events: any[] = []
    await streamSse('/api/x', { content: 'hi' }, (e) => events.push(e))

    expect(events.map(e => e.event)).toEqual(['meta', 'message_delta'])
    expect(events[1].data.content).toBe('A')
  })
})
```

- [ ] **Step 3: 运行确认失败**

```bash
cd frontend && npx vitest run
```
Expected: FAIL（sse.ts 不存在）

- [ ] **Step 4: 实现 SSE 解析器与 REST 封装**

`frontend/src/api/sse.ts`：

```ts
import type { SseEvent } from '../types'

export function parseSseBlock(block: string): SseEvent | null {
  let event = 'message'
  let data = ''
  for (const line of block.split('\n')) {
    if (line.startsWith(':') || line.trim() === '') continue
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!data) return null
  try {
    return { event, data: JSON.parse(data) }
  } catch {
    return null
  }
}

export async function streamSse(
  url: string,
  body: unknown,
  onEvent: (ev: SseEvent) => void,
): Promise<void> {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status}`)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const ev = parseSseBlock(block)
      if (ev) onEvent(ev)
    }
  }
  const tail = parseSseBlock(buffer)
  if (tail) onEvent(tail)
}
```

`frontend/src/api/rest.ts`：

```ts
const json = async (url: string, init?: RequestInit) => {
  const resp = await fetch(url, init)
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
  return resp.json()
}

export const listConversations = () => json('/api/conversations')
export const createConversation = (title?: string) =>
  json('/api/conversations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  })
export const deleteConversation = (id: number) =>
  fetch(`/api/conversations/${id}`, { method: 'DELETE' })
export const getTurns = (id: number) => json(`/api/conversations/${id}/turns`)
```

- [ ] **Step 5: 运行确认通过**

```bash
cd frontend && npx vitest run
```
Expected: PASS（3 组用例全绿）

- [ ] **Step 6: 组件与主界面**

`frontend/src/components/ThinkingBlock.vue`：

```vue
<script setup lang="ts">
import { ref } from 'vue'
const props = defineProps<{ content: string; streaming?: boolean }>()
const open = ref(false)
</script>

<template>
  <div class="thinking">
    <button class="toggle" @click="open = !open">
      {{ streaming ? '💭 思考中…' : '💭 思考过程' }} {{ open ? '▾' : '▸' }}
    </button>
    <pre v-show="open">{{ content }}</pre>
  </div>
</template>

<style scoped>
.thinking { margin: 4px 0; }
.toggle {
  border: none; background: #f0f0f0; border-radius: 6px;
  padding: 2px 10px; font-size: 12px; color: #666; cursor: pointer;
}
pre {
  background: #fafafa; border: 1px dashed #ddd; border-radius: 6px;
  padding: 8px; font-size: 12px; white-space: pre-wrap; margin: 4px 0;
}
</style>
```

`frontend/src/components/ToolCard.vue`：

```vue
<script setup lang="ts">
import { ref } from 'vue'
const props = defineProps<{ tool: { toolName: string; arguments?: string; result?: string; success?: boolean; durationMs?: number; callId: string } }>()
const open = ref(false)
</script>

<template>
  <div class="tool-card" :class="{ failed: tool.success === false }">
    <button class="toggle" @click="open = !open">
      🔧 {{ tool.toolName }}
      <span v-if="tool.durationMs"> · {{ tool.durationMs }}ms</span>
      <span v-if="tool.success === false"> · 失败</span>
    </button>
    <div v-show="open" class="detail">
      <pre v-if="tool.arguments">入参: {{ tool.arguments }}</pre>
      <pre v-if="tool.result">结果: {{ tool.result }}</pre>
    </div>
  </div>
</template>

<style scoped>
.tool-card { margin: 4px 0; }
.tool-card.failed .toggle { color: #c00; }
.toggle {
  border: 1px solid #d0e0f0; background: #f5f9ff; border-radius: 6px;
  padding: 2px 10px; font-size: 12px; cursor: pointer;
}
.detail pre {
  background: #fafafa; border-radius: 6px; padding: 6px;
  font-size: 12px; white-space: pre-wrap;
}
</style>
```

`frontend/src/components/MessageBubble.vue`：

```vue
<script setup lang="ts">
defineProps<{ role: 'user' | 'assistant'; content: string }>()
</script>

<template>
  <div class="bubble" :class="role">
    <div class="content">{{ content }}</div>
  </div>
</template>

<style scoped>
.bubble { display: flex; margin: 8px 0; }
.user { justify-content: flex-end; }
.assistant { justify-content: flex-start; }
.content {
  max-width: 80%; border-radius: 10px; padding: 8px 12px;
  white-space: pre-wrap; line-height: 1.6;
}
.user .content { background: #95ec69; }
.assistant .content { background: #f0f0f0; }
</style>
```

`frontend/src/App.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { streamSse } from './api/sse'
import { createConversation, getTurns, listConversations } from './api/rest'
import type { ChatTurn, ToolEvent, TurnRecord } from './types'
import ThinkingBlock from './components/ThinkingBlock.vue'
import ToolCard from './components/ToolCard.vue'
import MessageBubble from './components/MessageBubble.vue'

interface ConversationItem { id: number; title: string; turnCount: number; updatedAt: string }

const conversations = ref<ConversationItem[]>([])
const activeId = ref<number | null>(null)
const input = ref('')
const sending = ref(false)
const turns = ref<ChatTurn[]>([])

const newChat = async () => {
  const c = await createConversation()
  await refresh()
  await select(c.id)
}

const refresh = async () => {
  conversations.value = await listConversations()
}

const select = async (id: number) => {
  activeId.value = id
  turns.value = []
  const history: TurnRecord[] = await getTurns(id)
  for (const t of history) {
    const turn: ChatTurn = { userText: '', thinking: '', text: '', tools: [], status: 'done' }
    for (const m of t.messages) {
      if (m.msgType === 'USER') turn.userText = m.content ?? ''
      else if (m.msgType === 'THINKING') turn.thinking += (m.content ?? '') + '\n'
      else if (m.msgType === 'TEXT') turn.text += m.content ?? ''
      else if (m.msgType === 'TOOL_CALL') turn.tools.push({ callId: m.callId ?? '', toolName: m.toolName ?? '', arguments: m.arguments })
      else if (m.msgType === 'TOOL_RESULT') {
        const target = turn.tools.find(x => x.callId === m.callId)
        if (target) Object.assign(target, { result: m.result, success: m.success, durationMs: m.durationMs })
      }
    }
    turns.value.push(turn)
  }
}

const send = async () => {
  if (!input.value.trim() || !activeId.value || sending.value) return
  const text = input.value
  input.value = ''
  sending.value = true
  const turn: ChatTurn = { userText: text, thinking: '', text: '', tools: [], status: 'streaming' }
  turns.value.push(turn)
  try {
    await streamSse(`/api/conversations/${activeId.value}/chat`, { content: text }, (ev) => {
      if (ev.event === 'thinking_delta') turn.thinking += ev.data.content
      else if (ev.event === 'message_delta') turn.text += ev.data.content
      else if (ev.event === 'tool_call') turn.tools.push({ callId: ev.data.callId, toolName: ev.data.toolName, arguments: ev.data.arguments })
      else if (ev.event === 'tool_result') {
        const target = turn.tools.find(x => x.callId === ev.data.callId)
        if (target) Object.assign(target, ev.data)
      } else if (ev.event === 'turn_done') turn.status = 'done'
      else if (ev.event === 'error') { turn.status = 'error'; console.error(ev.data) }
    })
    if (turn.status === 'streaming') turn.status = 'done'
    refresh()
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  await refresh()
  if (conversations.value.length === 0) await newChat()
  else await select(conversations.value[0].id)
})
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <button class="new" @click="newChat">＋ 新会话</button>
      <div v-for="c in conversations" :key="c.id"
           class="conv" :class="{ active: c.id === activeId }" @click="select(c.id)">
        {{ c.title }} <small>{{ c.turnCount }} 轮</small>
      </div>
    </aside>
    <main class="chat">
      <div class="timeline">
        <div v-for="(turn, i) in turns" :key="i" class="turn">
          <MessageBubble role="user" :content="turn.userText" v-if="turn.userText" />
          <ThinkingBlock :content="turn.thinking" :streaming="turn.status === 'streaming'" v-if="turn.thinking" />
          <ToolCard v-for="t in turn.tools" :key="t.callId" :tool="t" />
          <MessageBubble role="assistant" :content="turn.text" v-if="turn.text" />
        </div>
      </div>
      <div class="composer">
        <textarea v-model="input" @keydown.enter.exact.prevent="send"
                  placeholder="输入消息，Enter 发送" :disabled="sending" />
        <button @click="send" :disabled="sending || !activeId">发送</button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100vh; }
.sidebar { width: 220px; border-right: 1px solid #eee; padding: 12px; overflow-y: auto; }
.new { width: 100%; padding: 8px; border-radius: 8px; border: none; background: #1a73e8; color: #fff; cursor: pointer; }
.conv { padding: 8px; border-radius: 8px; cursor: pointer; margin-top: 4px; font-size: 14px; }
.conv.active { background: #e8f0fe; }
.chat { flex: 1; display: flex; flex-direction: column; }
.timeline { flex: 1; overflow-y: auto; padding: 16px; }
.composer { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #eee; }
.composer textarea { flex: 1; resize: none; height: 60px; border-radius: 8px; border: 1px solid #ccc; padding: 8px; }
</style>
```

`frontend/src/main.ts`：

```ts
import { createApp } from 'vue'
import App from './App.vue'

createApp(App).mount('#app')
```

- [ ] **Step 7: 手动联调（冒烟）**

```bash
# 终端 1：
mvn -q -pl web spring-boot:run
# 终端 2：
cd frontend && npm run dev
```
Expected: 浏览器打开 `http://localhost:5173`，新建会话发送"列出工作区里有什么文件"，界面出现 thinking 折叠块、list_dir 工具卡片、最终回答；PG 中可查到该轮完整 turn/message 记录

- [ ] **Step 8: Commit**

```bash
cd /Users/newbeefly/Coder/Project/Claude/javaAgent
git add frontend/
git commit -m "feat: Vue3 聊天前端（SSE 解析/会话列表/thinking-工具-消息分区渲染）"
```

---

### Task 13: 端到端测试 + README

**Files:**
- Create: `web/src/test/java/com/javaagent/web/e2e/AgentEndToEndTest.java`
- Create: `README.md`
- Modify: `web/src/test/java/.../AgentEndToEndTest.java`（打桩 ChatModel 脚本化返回）

**Interfaces:**
- Consumes: 全部前序任务产物
- Produces: 覆盖验收标准 1/2/3/4 的自动化 E2E；README（启动方式：PG 前置、`mvn spring-boot:run`、`npm run dev`）

- [ ] **Step 1: 写 E2E 测试（Testcontainers PG + 脚本化 ChatModel + WebTestClient）**

`web/src/test/java/com/javaagent/web/e2e/AgentEndToEndTest.java`：

```java
package com.javaagent.web.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class AgentEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        org.springframework.ai.chat.model.ChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    /**
     * 脚本：第 1 次调用返回 list_dir 工具调用；第 2 次返回最终文本。
     */
    static class ScriptedChatModel implements org.springframework.ai.chat.model.ChatModel {

        final AtomicInteger calls = new AtomicInteger();
        final List<org.springframework.ai.chat.prompt.Prompt> prompts = new CopyOnWriteArrayList<>();

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            prompts.add(prompt);
            if (calls.incrementAndGet() == 1) {
                var toolCall = new org.springframework.ai.chat.messages.ToolCall(
                    "call-1", "list_dir", "{\"path\":\".\"}");
                var assistant = new org.springframework.ai.chat.messages.AssistantMessage(
                    "", Map.of(), List.of(toolCall));
                return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(assistant)));
            }
            var finalMsg = new org.springframework.ai.chat.messages.AssistantMessage("工作区里有 1 个文件");
            return new org.springframework.ai.chat.model.ChatResponse(
                List.of(new org.springframework.ai.chat.model.Generation(finalMsg)));
        }

        @Override
        public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    @Test
    void fullTurnWithToolCallPersistsAndStreams() {
        // 1. 创建会话
        Long convId = webTestClient.post().uri("/api/conversations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "E2E"))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class)
            .returnResult().getResponseBody() != null
                ? ((Number) ((Map<?, ?>) expectBodyMap(webTestClient)).get("id")).longValue()
                : null;

        // 2. 发起对话（SSE）
        String body = webTestClient.post().uri("/api/conversations/%d/chat".formatted(convId))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("content", "列出工作区文件"))
            .exchange().expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String.class).returnResult().getResponseBody();

        // 断言事件链：工具调用 → 工具结果 → turn_done
        org.assertj.core.api.Assertions.assertThat(body)
            .contains("event:tool_call").contains("list_dir")
            .contains("event:tool_result")
            .contains("event:turn_done");

        // 3. 断言 PG 完整落库：USER + TOOL_CALL + TOOL_RESULT + TEXT
        Integer messageCount = jdbcTemplate.queryForObject(
            "select count(*) from message where msg_type in ('USER','TOOL_CALL','TOOL_RESULT','TEXT')",
            Integer.class);
        org.assertj.core.api.Assertions.assertThat(messageCount).isGreaterThanOrEqualTo(4);

        // 4. 回放接口包含全部消息类型
        webTestClient.get().uri("/api/conversations/%d/turns".formatted(convId))
            .exchange().expectStatus().isOk()
            .expectBody(String.class)
            .value(b -> org.assertj.core.api.Assertions.assertThat(b)
                .contains("USER").contains("TOOL_CALL").contains("TOOL_RESULT").contains("TEXT"));
    }

    private Object expectBodyMap(WebTestClient client) {
        return client.post().uri("/api/conversations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "E2E"))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class)
            .returnResult().getResponseBody();
    }
}
```

注：Step 1 的会话创建写法有冗余（expectBodyMap 重复建会话）——实现时简化为一次创建取 id：

```java
Map<?, ?> created = webTestClient.post().uri("/api/conversations")
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(Map.of("title", "E2E"))
    .exchange().expectStatus().isOk()
    .expectBody(Map.class).returnResult().getResponseBody();
Long convId = ((Number) created.get("id")).longValue();
```

（删掉 expectBodyMap 私有方法与三目表达式，直接用上面的四行。）

- [ ] **Step 2: 运行 E2E**

```bash
mvn -q -pl web test -Dtest=AgentEndToEndTest
```
Expected: PASS（若 SAA ReactAgent 对 stub ChatModel 的调用次数/行为与脚本不符——例如工具循环终止条件不同——按实际交互调整脚本序列，验收断言不变）

- [ ] **Step 3: README**

`README.md`：

```markdown
# javaAgent

基于 Spring AI Alibaba 1.1.2.3 ReactAgent 的 ReAct Agent 服务。

## 架构

- `agent/`：Agent 引擎（ReactAgent、技能、工具、持久化、压缩）
- `web/`：REST + SSE 接口层（可启动模块）
- `frontend/`：Vue3 聊天界面
- `skills/`：本地技能目录（`resident: true` 常驻注入，其余 read_skill 渐进加载）

## 启动

前置：JDK 21、Maven 3.8+、Node 18+、PostgreSQL（本库 `javaagent`，建库见下）与
`web/src/main/resources/application-local.yml`（含 PG 与 StepFun 密钥，已 gitignore）。

```bash
psql -h localhost -U jiege -d postgres -c "CREATE DATABASE javaagent OWNER jiege;"
mvn -pl web spring-boot:run        # 后端 :8080
cd frontend && npm install && npm run dev   # 前端 :5173（代理 /api）
```

## 测试

```bash
mvn test -DexcludedGroups=manual          # 单元 + Testcontainers
mvn -pl agent test -Dgroups=manual        # StepFun 流式探针（需真实 key）
cd frontend && npx vitest run
```
```

- [ ] **Step 4: 全量回归**

```bash
mvn -q test -DexcludedGroups=manual && cd frontend && npx vitest run
```
Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add web/src README.md
git commit -m "test: 端到端用例（工具调用全链路流式+落库+回放）与 README"
```

---

## 验收标准对照（spec §11 → 任务）

| spec 验收 | 任务 |
|---|---|
| 1 前端分区渲染 thinking/message/tool | Task 12（组件 + 冒烟） |
| 2 轮次完整落库（thinking/工具/正文） | Task 7/8 + Task 13 E2E |
| 3 渐进技能 read_skill、常驻技能注入 | Task 5 + Task 12 冒烟 |
| 4 groupedTools 渐进工具 | Task 6（装配）+ Task 5（csv-analysis 技能） |
| 5 重启后恢复会话 | Task 10（checkpoint saver） |
| 6 压缩触发、回放完整 | Task 11 |
| 7 工具失败不断流 | Task 8（EventEmittingToolInterceptor catch 分支） |

---

## Errata（自审修正，实施时优先按此执行）

1. **迁移文件位置**：DDL 位于 `agent/src/main/resources/db/migration/`（Task 7 Step 1 注），Task 2 的文件路径以 Task 7 注为准（web 启动时 Flyway 执行 agent classpath 里的迁移）。Task 1 的 `application.yml` 因此追加 `spring.flyway.locations: classpath:db/migration`。

2. **压缩落地形态**（Task 11 Step 3 注的正式化）：
   - `V2__compaction.sql`：`ALTER TABLE conversation ADD COLUMN compact_summary TEXT;`
   - `Conversation` record 追加字段 `String compactSummary`（Task 7 定义为 5 字段 record，实施 Task 11 时扩为 6 字段，所有构造点同步）
   - `CompactionService` 不建引导轮：summary 写 `conversation.compact_summary`，返回新 threadId
   - `AgentFacade.chat` 压缩后：重新读 conversation（拿新 threadId），并在该线程首轮输入前注入 `SystemMessage("此前对话摘要：" + compactSummary)`（ReactAgent 输入消息列表前置）
   - Task 11 测试断言同步改为：`compactIfNeeded` 后 `conversations.save` 的实体 `compactSummary` 非空、threadId 带 `-v1`

3. **会话删除级联 checkpoint**（spec §5 DELETE 行）：Task 9 的 `delete` 在 Task 10 完成后补一行——按 saver 的存储表/键删除该 conversation 全部 threadId 对应 checkpoint（实现时按 saver 实际存储结构写 SQL 或调 saver 删除 API；若无删除 API，用 JdbcTemplate 按表删）。

4. **两个"编译期验证点"不是占位**：Task 8 Step 9 的 `StubAgent.stubStreamingOutput` 与 Task 10 Step 2 的占位断言，是 SAA 1.1.2.3 内部构造器无法在计划期确认的诚实处理——实现者必须在该步骤完成真实桩/断言后才能继续，验收标准不变（测试全绿）。

5. **Task 9 ConversationController.delete**：删除误写的 `@org.springframework.http.HttpStatus` 行，方法注解用 `@ResponseStatus(HttpStatus.NO_CONTENT)`，返回 `void`。
