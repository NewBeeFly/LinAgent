package com.javaagent.agent.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.javaagent.agent.skills.FilteredSkillRegistry;
import com.javaagent.agent.skills.SkillDefinition;
import com.javaagent.agent.skills.SkillManifestScanner;
import com.javaagent.agent.tools.FileTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Set;

@Configuration
public class AgentBeansConfig {

    @Bean
    public MemorySaver checkpointSaver() {
        // Task 10 替换为 PG JDBC saver（AgentFactory 注入处只依赖 BaseCheckpointSaver 接口）
        return new MemorySaver();
    }

    @Bean
    public FileTools fileTools(@Value("${agent.workspace-root:./workspace}") String workspaceRoot) {
        return new FileTools(Path.of(workspaceRoot));
    }

    @Bean
    public FilteredSkillRegistry progressiveSkillRegistry(
            SkillManifestScanner scanner,
            @Value("${agent.skills-root:./skills}") String skillsRoot) {
        FileSystemSkillRegistry inner = FileSystemSkillRegistry.builder()
            .projectSkillsDirectory(Path.of(skillsRoot).toAbsolutePath().toString())
            .build();
        Set<String> residentNames = Set.copyOf(scanner.scan(Path.of(skillsRoot)).stream()
            .filter(SkillDefinition::resident)
            .map(SkillDefinition::name)
            .toList());
        return new FilteredSkillRegistry(inner, residentNames);
    }
}
