package com.linagent.agent.skills;

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

    @Test
    void missingSkillsRootLogsWarnAndScansAsEmpty() {
        Path missing = tempDir.resolve("no-such-skills");
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SkillManifestScanner.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(new SkillManifestScanner().scan(missing)).isEmpty();
            assertThat(appender.list)
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(e.getFormattedMessage()).contains(missing.toAbsolutePath().toString());
                });
        } finally {
            logger.detachAppender(appender);
        }
    }
}
