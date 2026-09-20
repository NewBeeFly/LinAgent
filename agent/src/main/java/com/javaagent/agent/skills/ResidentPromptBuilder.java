package com.javaagent.agent.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 常驻技能全文（拼入 system prompt 静态区）。system prompt 模板文件化，
 * {resident_skills} 占位符在加载时替换，结果缓存（invalidateCache 供 autoReload 场景重扫）。
 */
@Component
public class ResidentPromptBuilder {

    private final SkillManifestScanner scanner;
    private final Path skillsRoot;
    private final String promptTemplate;
    private volatile String cached;

    public ResidentPromptBuilder(SkillManifestScanner scanner,
                                 @Value("${agent.skills-root:./skills}") String skillsRoot,
                                 @Value("${agent.prompt-template:prompts/system-prompt.md}") String promptTemplate) {
        this.scanner = scanner;
        this.skillsRoot = Path.of(skillsRoot);
        this.promptTemplate = promptTemplate;
        this.cached = build();
    }

    /**
     * 模板读取：classpath 优先（UTF-8 流式），文件系统回退。
     * fat-jar 场景下资源以 jar:URL 暴露，URL.getFile() 不是合法文件路径
     * （Path.of 抛异常），必须经 openStream 读取——Task 12 实测坑点。
     */
    static String readTemplate(String path, ClassLoader classLoader) throws IOException {
        URL resource = classLoader.getResource(path);
        if (resource != null) {
            try (InputStream in = resource.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return Files.readString(Path.of(path));
    }

    public String build() {
        String snapshot = cached;
        if (snapshot != null && !snapshot.isBlank()) {
            return snapshot;
        }
        try {
            String template = readTemplate(promptTemplate, getClass().getClassLoader());
            List<SkillDefinition> resident = scanner.scan(skillsRoot).stream()
                .filter(SkillDefinition::resident).toList();
            String block = resident.stream()
                .map(d -> "### 技能：" + d.name() + "\n" + d.content().strip())
                .collect(Collectors.joining("\n\n"));
            String result = template.replace("{resident_skills}", block);
            this.cached = result;
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("加载 system prompt 模板失败", e);
        }
    }

    /** autoReload 场景：清缓存后下次 build 重新扫描 */
    public void invalidateCache() {
        cached = null;
    }
}
