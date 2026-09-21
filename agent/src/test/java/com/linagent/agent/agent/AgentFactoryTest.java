package com.linagent.agent.agent;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.linagent.agent.skills.FilteredSkillRegistry;
import com.linagent.agent.skills.ResidentPromptBuilder;
import com.linagent.agent.skills.SkillManifestScanner;
import com.linagent.agent.stream.OpenAiThinkingExtractor;
import com.linagent.agent.stream.ThinkingTapChatModelTest;
import com.linagent.agent.tools.FileTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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

        // promptTemplate 命中 test classpath 下的真实模板（agent main resources）
        ResidentPromptBuilder promptBuilder = new ResidentPromptBuilder(
            new SkillManifestScanner(), skillsRoot.toString(), "prompts/system-prompt.md");

        // 真实 FilteredSkillRegistry（空常驻过滤），避免 hook 装配对 null 敏感
        FilteredSkillRegistry registry = new FilteredSkillRegistry(
            FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(skillsRoot.toAbsolutePath().toString())
                .build(),
            Set.of());

        AgentFactory factory = new AgentFactory(
            new ThinkingTapChatModelTest.ChatModelStub(),
            new OpenAiThinkingExtractor(),
            new FileTools(tempDir.resolve("workspace")),
            registry,
            promptBuilder,
            new MemorySaver(),
            tempDir.resolve("workspace").toString());

        AgentFactory.AgentHandle handle =
            factory.create(Sinks.many().unicast().onBackpressureBuffer(), new AtomicReference<>());

        assertThat(handle.agent()).isNotNull();
        assertThat(handle.tappedModel()).isNotNull();
        // ReactAgent 不暴露 systemPrompt 读取口，装配产物随 handle 携带后断言常驻正文
        assertThat(handle.systemPrompt()).contains("常驻技能正文");
    }
}
