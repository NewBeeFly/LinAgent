package com.javaagent.agent.skills;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        try (var stream = Files.list(skillsRoot)) {
            return stream.filter(Files::isDirectory)
                .map(dir -> dir.resolve("SKILL.md"))
                .filter(Files::isRegularFile)
                .map(this::parse)
                .flatMap(Optional::stream)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描技能目录失败: " + skillsRoot, e);
        }
    }

    private Optional<SkillDefinition> parse(Path skillMd) {
        try {
            String raw = Files.readString(skillMd);
            Matcher m = FRONTMATTER.matcher(raw);
            if (!m.matches()) {
                return Optional.empty();
            }
            String frontmatter = m.group(1);
            String content = m.group(2);
            Matcher name = NAME.matcher(frontmatter);
            Matcher desc = DESCRIPTION.matcher(frontmatter);
            if (!name.find() || !desc.find()) {
                return Optional.empty();
            }
            boolean resident = RESIDENT.matcher(frontmatter).find();
            return Optional.of(new SkillDefinition(
                name.group(1).trim(), desc.group(1).trim(), resident, skillMd.getParent(), content));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
