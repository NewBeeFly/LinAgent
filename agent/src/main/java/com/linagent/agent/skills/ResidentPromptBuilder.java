package com.linagent.agent.skills;

import com.linagent.agent.config.ProjectPathResolver;
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
        this.skillsRoot = ProjectPathResolver.resolveDir(skillsRoot);
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
        chatCached = null;
    }

    private volatile String chatCached;

    /**
     * CHAT（纯聊档）专用精简模板：正向声明纯对话助手、全文无工具说明——实测（2026-09-24）
     * 通用串+尾部否定追加压不住对话历史里的工具模仿，前文几百行工具说明必须整体移除。
     * 不做技能注入（纯聊档不挂技能 hook）。
     */
    public String buildChat() {
        String snapshot = chatCached;
        if (snapshot != null && !snapshot.isBlank()) {
            return snapshot;
        }
        try {
            String result = readTemplate(CHAT_PROMPT_TEMPLATE, getClass().getClassLoader());
            this.chatCached = result;
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("加载纯聊 system prompt 模板失败", e);
        }
    }

    static final String CHAT_PROMPT_TEMPLATE = "prompts/system-prompt-chat.md";
}
