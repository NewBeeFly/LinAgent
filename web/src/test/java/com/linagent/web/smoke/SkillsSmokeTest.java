package com.linagent.web.smoke;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.linagent.web.support.TestAuth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终审 Important 3：技能链路运行时冒烟（@Tag("manual")，默认排除，显式触发）。
 *
 * 与单测/Mock 验证的区别：本用例起完整 web 上下文（真实 StepFun step-3.7-flash 模型 +
 * 本地 PG linagent 库 + 真实 SkillsAgentHook），验证「read_skill 渐进加载 →
 * csv-analysis 技能激活 → csv_summary 分组工具出现并被调用」这条只有真实模型才会走的链路。
 *
 * 前置（manual 运行环境，不进 CI）：
 * 1. 本地 PG 可达：localhost:5432/linagent（容器 pg-jiege，flyway 启动幂等补齐 schema）
 * 2. web/src/main/resources/application-local.yml 提供真实 STEPFUN_API_KEY（已 gitignore）
 * 3. 身份：v0.2 起所有请求必须带身份 header（app_user 校验，未知 401）。本测试默认身份
 *    default/linmj（V4 种子）已由 @BeforeEach 统一注入（TestAuth.LINMJ）；curl 手工验证
 *    同样必须带 x-tenant-id / x-user-id 两个 header。
 *
 * 运行：mvn -pl web test -Dtest=SkillsSmokeTest -Dgroups=manual
 * （需要本地仓库已 install agent 模块：mvn -pl agent install -DskipTests）
 */
@Tag("manual")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SkillsSmokeTest {

    /** 真实运行结论（证据摘录自 2026-09-20 实际运行，详见 .superpowers/sdd/.../final-fix-report.md） */
    static final String CONCLUSION = """
        2026-09-20 真实运行（StepFun step-3.7-flash，本地 PG pg-jiege，第 1 次尝试即通过，全程约 7s）：
        SSE 事件流顺序出现 read_skill 的 tool_call → 技能全文 tool_result → csv_summary 的
        tool_call + tool_result（真实执行：『总行数(不含表头) 2，列数 2，表头: name, score』）→
        message_delta 输出非空 Markdown 统计结论 → turn_done（usage totalTokens=2459）。
        证据摘录（原样，仅截取关键事件）：
          event:tool_call
          data:{"turnId":5,"callId":"chatcmpl-tool-a92b74b1bd7f1d5d","toolName":"read_skill","arguments":"{\\"skill_name\\": \\"csv-analysis\\"}","seq":23}

          event:tool_result
          data:{"turnId":5,"callId":"chatcmpl-tool-a92b74b1bd7f1d5d","toolName":"read_skill","result":"\\"# CSV 分析技能\\\\n\\\\n读取本技能后，可使用 csv_summary 工具快速统计 CSV 文件：...\\"","durationMs":2,"success":true,"seq":24}

          event:tool_call
          data:{"turnId":5,"callId":"chatcmpl-tool-abb772e2d7b2fcac","toolName":"csv_summary","arguments":"{\\"path\\": \\"data.csv\\"}","seq":37}

          event:tool_result
          data:{"turnId":5,"callId":"chatcmpl-tool-abb772e2d7b2fcac","toolName":"csv_summary","result":"\\"总行数(不含表头) 2，列数 2，表头: name, score\\"","durationMs":2,"success":true,"seq":38}

          event:turn_done
          data:{"turnId":5,"finishReason":"STOP","usage":{"promptTokens":2355,"completionTokens":104,"totalTokens":2459},"seq":73}

        同日第二次运行（含 turn.usage 落库复验）同样第 1 次尝试通过，DB 实测：
          turn.usage = {"totalTokens": 2587, "promptTokens": 2375, "completionTokens": 212}
        （JSONB 键序由 PG 归一化，字段与 AgentEvent.Usage record 一致）
        """;

    /** 冒烟工作区（v0.2 多租户布局）：
     *  个人根 {root}/default/users/linmj/ 放 data.csv（表头 + 2 行）——工具实例随轮构造、
     *  根已烤入 personalRoot，扁平基根下放种子不再被 csv_summary 读到；
     *  租户共享区 {root}/default/shared/ 放 团队规范.txt——WorkspaceResolver 幂等 provision
     *  会在个人根内建 shared → ../../shared 相对符号链接，此文件可验证共享区挂载。 */
    static final Path workspaceRoot = createWorkspace();

    static Path createWorkspace() {
        try {
            Path dir = Files.createTempDirectory("skills-smoke-workspace");
            Path personal = Files.createDirectories(
                dir.resolve("default").resolve("users").resolve("linmj"));
            Files.writeString(personal.resolve("data.csv"), "name,score\nalice,90\nbob,85\n");
            Path shared = Files.createDirectories(dir.resolve("default").resolve("shared"));
            Files.writeString(shared.resolve("团队规范.txt"), "团队规范：共享区挂载验证种子\n");
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("构造冒烟工作区失败", e);
        }
    }

    /** skills 根定位：从 user.dir 逐级向上找含 skills/csv-analysis 的目录（surefire cwd 是 web 模块） */
    static Path projectSkillsRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("skills").resolve("csv-analysis"))) {
                return dir.resolve("skills");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到项目 skills 目录（请从项目内运行测试）");
    }

    @DynamicPropertySource
    static void agentProps(DynamicPropertyRegistry registry) {
        registry.add("agent.workspace-root", () -> workspaceRoot.toString());
        registry.add("agent.skills-root", () -> projectSkillsRoot().toString());
    }

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 鉴权链（Task 3）：全上下文真实 HeaderUserAuthenticator + V4 种子（default/linmj），
     *  只补默认身份 header；原有 responseTimeout mutate 链合并进同一次 */
    @BeforeEach
    void auth() {
        webTestClient = webTestClient.mutate()
            .responseTimeout(Duration.ofSeconds(300))
            .defaultHeaders(TestAuth.LINMJ)
            .build();
    }

    @Test
    void skillsChainLoadsCsvAnalysisThenRunsCsvSummaryTool() {

        // 真实模型存在不触发工具的随机性：明确指令 + 最多 3 次重试（每次全新会话，互不污染）
        List<String> attemptBodies = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            Map<?, ?> created = webTestClient.post().uri("/api/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "技能冒烟-" + attempt))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
            Long convId = ((Number) created.get("id")).longValue();
            try {
                String body = webTestClient.post()
                    .uri("/api/conversations/%d/chat".formatted(convId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("content",
                        "请先用 read_skill 工具加载 csv-analysis 技能，"
                            + "然后用 csv_summary 工具统计 data.csv，最后给出统计结论。"))
                    .exchange().expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                    .expectBody(String.class).returnResult().getResponseBody();

                attemptBodies.add(body);
                System.out.println("[SkillsSmokeTest] attempt " + attempt + " SSE body:\n" + body);

                // 链路断言：read_skill 加载 → csv_summary 激活后调用并返回真实统计 → 正文 + 收尾
                assertThat(body).contains("read_skill");
                assertThat(body).contains("csv_summary");
                assertThat(body).contains("event:tool_result");
                // csv_summary 真实执行（FileTools 工作区内）：2 行数据、2 列、表头 name/score
                assertThat(body).contains("列数 2");
                assertThat(body).contains("name");
                assertThat(body).contains("event:message_delta");
                assertThat(body).contains("event:turn_done");
                // 顺带复验真实模型的 turn.usage 落库（终审 Important 2 的运行时形态）
                String usageJson = jdbcTemplate.queryForObject(
                    "select usage from turn where conversation_id = ? order by seq desc limit 1",
                    String.class, convId);
                System.out.println("[SkillsSmokeTest] turn.usage = " + usageJson);
                assertThat(usageJson).contains("totalTokens");
                return; // 首个成功尝试即通过
            } finally {
                // 冒烟不留脏数据：级联清 checkpoint + 会话行（本测试同时复验删除链路）
                webTestClient.delete().uri("/api/conversations/%d".formatted(convId))
                    .exchange().expectStatus().isNoContent();
            }
        }
        throw new AssertionError("3 次尝试均未走通技能链路，最后一次 SSE 输出：\n" + attemptBodies.get(2));
    }
}
