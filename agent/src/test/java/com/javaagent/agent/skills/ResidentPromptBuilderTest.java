package com.javaagent.agent.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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

    @Test
    void buildRecachesAfterInvalidationSoSubsequentBuildsSkipRescan() throws Exception {
        Path skillsRoot = writeSkills();
        Path template = tempDir.resolve("system-prompt.md");
        Files.writeString(template, "{resident_skills}");

        ResidentPromptBuilder builder = new ResidentPromptBuilder(
            new SkillManifestScanner(), skillsRoot.toString(), template.toString());

        builder.invalidateCache();
        String rebuilt = builder.build();
        assertThat(rebuilt).contains("# 常驻正文");

        // invalidate 后首次 build 已回写缓存：再次变更文件不应被感知（不重扫）
        Files.writeString(skillsRoot.resolve("resident-skill").resolve("SKILL.md"), """
            ---
            name: resident-skill
            description: 常驻技能
            resident: true
            ---
            # 未再次失效时的新正文
            """);
        assertThat(builder.build()).isSameAs(rebuilt);
    }

    @Test
    void readTemplateReadsClasspathResourceLivingInJarViaStream() throws Exception {
        // fat-jar 场景：模板资源以 jar:URL 暴露，getFile() 无法转 Path，必须 openStream 读取
        Path jar = tempDir.resolve("templates.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("prompts/jar-template.md"));
            out.write("jar 内模板\n{resident_skills}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{ jar.toUri().toURL() }, null)) {
            String template = ResidentPromptBuilder.readTemplate("prompts/jar-template.md", loader);
            assertThat(template).contains("jar 内模板").contains("{resident_skills}");
        }
    }

    @Test
    void readTemplateFallsBackToFilesystemWhenClasspathMisses() throws Exception {
        // 自定义模板走文件系统：绝对路径不在 classpath，回退 Files.readString
        Path template = tempDir.resolve("custom-prompt.md");
        Files.writeString(template, "自定义模板 {resident_skills}");
        assertThat(ResidentPromptBuilder.readTemplate(template.toString(), getClass().getClassLoader()))
            .contains("自定义模板");
    }

    @Test
    void buildResolvesDefaultTemplateFromClasspathWhenNotAFileOnDisk() throws Exception {
        // 默认模板 prompts/system-prompt.md 位于 agent 模块 classpath（非工作目录文件）：
        // classpath 优先解析（与 fat-jar 内同一路径），占位符替换照常
        ResidentPromptBuilder builder = new ResidentPromptBuilder(
            new SkillManifestScanner(), writeSkills().toString(), "prompts/system-prompt.md");

        assertThat(builder.build())
            .contains("javaAgent")
            .contains("### 技能：resident-skill")
            .doesNotContain("{resident_skills}");
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
