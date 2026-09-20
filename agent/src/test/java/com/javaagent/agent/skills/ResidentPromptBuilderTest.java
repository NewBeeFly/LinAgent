package com.javaagent.agent.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResidentPromptBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildInjectsResidentSkillContentIntoTemplate() throws Exception {
        Path skillsRoot = writeSkills();
        Path template = tempDir.resolve("system-prompt.md");
        Files.writeString(template, "头部\n\n{resident_skills}\n\n尾部");

        ResidentPromptBuilder builder = new ResidentPromptBuilder(
            new SkillManifestScanner(), skillsRoot.toString(), template.toString());

        assertThat(builder.build())
            .contains("### 技能：resident-skill")
            .contains("# 常驻正文")
            .doesNotContain("{resident_skills}")
            .doesNotContain("渐进正文");
    }

    @Test
    void buildCachesResultAndInvalidateForcesRescan() throws Exception {
        Path skillsRoot = writeSkills();
        Path template = tempDir.resolve("system-prompt.md");
        Files.writeString(template, "{resident_skills}");

        ResidentPromptBuilder builder = new ResidentPromptBuilder(
            new SkillManifestScanner(), skillsRoot.toString(), template.toString());

        String first = builder.build();
        assertThat(builder.build()).isSameAs(first);

        Files.writeString(skillsRoot.resolve("resident-skill").resolve("SKILL.md"), """
            ---
            name: resident-skill
            description: 常驻技能
            resident: true
            ---
            # 更新后的常驻正文
            """);
        // 缓存生效：未失效前仍返回旧快照
        assertThat(builder.build()).isSameAs(first);

        builder.invalidateCache();
        assertThat(builder.build())
            .contains("# 更新后的常驻正文")
            .doesNotContain("# 常驻正文");
    }

    private Path writeSkills() throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Path resident = skillsRoot.resolve("resident-skill");
        Files.createDirectories(resident);
        Files.writeString(resident.resolve("SKILL.md"), """
            ---
            name: resident-skill
            description: 常驻技能
            resident: true
            ---
            # 常驻正文
            """);
        Path progressive = skillsRoot.resolve("progressive-skill");
        Files.createDirectories(progressive);
        Files.writeString(progressive.resolve("SKILL.md"), """
            ---
            name: progressive-skill
            description: 渐进技能
            ---
            # 渐进正文
            """);
        return skillsRoot;
    }
}
