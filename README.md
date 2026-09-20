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
mvn -pl web test -Dtest=SkillsSmokeTest -Dgroups=manual   # 技能链路冒烟（需本地 PG + 真实 key）
cd frontend && npx vitest run
```

`manual` 分组打真实 StepFun API：`StepFunStreamingProbeTest`（流式探针）与
`SkillsSmokeTest`（技能链路冒烟），默认排除、单独显式触发，避免单测依赖外部 LLM 服务。

`SkillsSmokeTest` 已于 2026-09-20 真实跑通（step-3.7-flash，第 1 次尝试通过）：
`read_skill` 加载 csv-analysis → `csv_summary` 真实执行（2 行/2 列/表头 name,score）→
Markdown 统计结论 → `turn_done`（usage 落库 `{"totalTokens":2587,...}`）。证据摘录见
测试类 `CONCLUSION` 常量与 `.superpowers/sdd/2026-09-20-react-agent/final-fix-report.md`。

