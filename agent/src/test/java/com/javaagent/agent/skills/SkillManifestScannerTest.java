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
