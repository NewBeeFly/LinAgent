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

注：`mvn -pl web spring-boot:run` 以本地仓库中的 agent jar 进入 classpath，
首次运行前需先 `mvn install -DskipTests` 安装各模块（system prompt 模板已按
jar:URL 兼容方式流式加载，无需 `-Dagent.prompt-template` 绕过）。前端端口以
Vite 启动输出为准（5173 被占用时自动切换下一个可用端口）。

## 测试

```bash
mvn test -DexcludedGroups=manual          # 单元 + Testcontainers（含 E2E）
mvn -pl agent test -Dgroups=manual        # StepFun 流式探针（需真实 key）
cd frontend && npx vitest run
```

`manual` 分组是打真实 StepFun API 的流式探针（`StepFunStreamingProbeTest`），
默认排除、单独显式触发，避免单测依赖外部 LLM 服务。
