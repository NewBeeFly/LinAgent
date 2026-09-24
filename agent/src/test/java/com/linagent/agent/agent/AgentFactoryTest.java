package com.linagent.agent.agent;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.linagent.agent.compaction.SummarizingModelHook;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.conversation.ChatMode;
import com.linagent.agent.skills.FilteredSkillRegistry;
import com.linagent.agent.skills.ResidentPromptBuilder;
import com.linagent.agent.skills.SkillManifestScanner;
import com.linagent.agent.stream.OpenAiThinkingExtractor;
import com.linagent.agent.stream.ThinkingTapChatModelTest;
import com.linagent.agent.workspace.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentFactoryTest {

    /** agent 模块无 TestAuth（web 测试设施），身份常量在本文件内自持 */
    private static final AuthContext CTX = new AuthContext("default", "linmj");

    @TempDir
    Path tempDir;

    /** 工作区基根（WorkspaceResolver 构造入参；个人根由此派生） */
    @TempDir
    Path workspaceTmp;

    @Test
    void createBuildsAgentWithResidentSkillsInSystemPrompt() throws Exception {
        AgentFactory factory = newFactory();

        AgentFactory.AgentHandle handle =
            factory.create(CTX, 1L, Sinks.many().unicast().onBackpressureBuffer(), new AtomicReference<>());

        assertThat(handle.agent()).isNotNull();
        assertThat(handle.tappedModel()).isNotNull();
        // ReactAgent 不暴露 systemPrompt 读取口，装配产物随 handle 携带后断言常驻正文
        assertThat(handle.systemPrompt()).contains("常驻技能正文");
        // 旧签名（无 mode 参）委托 STANDARD 档（测试兼容契约）
        assertThat(handle.mode()).isEqualTo(ChatMode.STANDARD);
    }

    /** v0.3 三档构造分支：handle 携带档位 + CHAT 追加纯聊声明（spec §2.3 原文），
     *  AUTO/STANDARD 的 system prompt 与缓存串一致（不追加）；三档 create 均不抛 */
    @Test
    void threeModeCreateBranchesAssemblyByChatMode() throws Exception {
        AgentFactory factory = newFactory();
        Sinks.Many<Object> sink = Sinks.many().unicast().onBackpressureBuffer();

        AgentFactory.AgentHandle standard = factory.create(CTX, 1L, sink, new AtomicReference<>(), ChatMode.STANDARD);
        AgentFactory.AgentHandle auto = factory.create(CTX, 2L, sink, new AtomicReference<>(), ChatMode.AUTO);
        AgentFactory.AgentHandle chat = factory.create(CTX, 3L, sink, new AtomicReference<>(), ChatMode.CHAT);

        // AgentHandle.mode：三档断言面（E2E/后续任务复用）
        assertThat(standard.mode()).isEqualTo(ChatMode.STANDARD);
        assertThat(auto.mode()).isEqualTo(ChatMode.AUTO);
        assertThat(chat.mode()).isEqualTo(ChatMode.CHAT);

        // CHAT：prompt = 缓存串 + 追加声明（防模型口头承诺做事——协议层无 tools）
        assertThat(chat.systemPrompt())
            .startsWith(standard.systemPrompt())
            .contains("系统未向你提供任何工具")
            .contains("不要输出工具调用格式");
        // AUTO/STANDARD：system prompt 保持缓存串原样
        assertThat(auto.systemPrompt()).isEqualTo(standard.systemPrompt());
    }

    /** Task 6：工具实例每轮构造烤入个人根——两轮 create 各自身份的个人工作区被 provision 出来 */
    @Test
    void createProvisionsPersonalWorkspacePerIdentity() throws Exception {
        AgentFactory factory = newFactory();

        factory.create(new AuthContext("t", "linmj"), 1L,
            Sinks.many().unicast().onBackpressureBuffer(), new AtomicReference<>());
        factory.create(new AuthContext("t", "tester"), 2L,
            Sinks.many().unicast().onBackpressureBuffer(), new AtomicReference<>());

        assertThat(Files.isDirectory(workspaceTmp.resolve("t/users/linmj"))).isTrue();
        assertThat(Files.isDirectory(workspaceTmp.resolve("t/users/tester"))).isTrue();
    }

    /** 既有构造段（chatModel 桩/真实 thinkingExtractor/registry/promptBuilder/saver）+ WorkspaceResolver */
    private AgentFactory newFactory() throws Exception {
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

        // promptTemplate 命中 test classpath 下的真实模板（agent main resources）
        ResidentPromptBuilder promptBuilder = new ResidentPromptBuilder(
            new SkillManifestScanner(), skillsRoot.toString(), "prompts/system-prompt.md");

        // 真实 FilteredSkillRegistry（空常驻过滤），避免 hook 装配对 null 敏感
        FilteredSkillRegistry registry = new FilteredSkillRegistry(
            FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(skillsRoot.toAbsolutePath().toString())
                .build(),
            Set.of());

        return new AgentFactory(
            new ThinkingTapChatModelTest.ChatModelStub(),
            new OpenAiThinkingExtractor(),
            registry,
            promptBuilder,
            new MemorySaver(),
            new WorkspaceResolver(workspaceTmp.toString()),
            // 大阈值=单测内永不触发压缩（hook 仅装配验证）
            new SummarizingModelHook(mock(org.springframework.ai.chat.model.ChatModel.class),
                ctx -> {}, promptBuilder, 1_000_000, 20, 2, Duration.ofSeconds(60)),
            // Task 5：审批规则装配入参——仓库 mock 默认返回空列表（无 user 规则，不触发审批判定路径）
            mock(com.linagent.agent.persistence.repository.PermissionRuleRepository.class),
            new com.linagent.agent.approval.InMemorySessionRules());
    }
}
