package com.linagent.agent.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.linagent.agent.skills.FilteredSkillRegistry;
import com.linagent.agent.skills.SkillDefinition;
import com.linagent.agent.skills.SkillManifestScanner;
import com.linagent.agent.tools.FileTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Set;

@Configuration
public class AgentBeansConfig {

    /**
     * PG 持久化 checkpoint saver（graph-core 内置 PostgresSaver）：
     * threadId=conversation.threadId 的 checkpoint 落 graphthread/graphcheckpoint 表，
     * 跨轮、跨进程（应用重启）均可恢复会话记忆。AgentFactory 注入处只依赖 BaseCheckpointSaver 接口。
     * 建表走 Flyway V2__checkpoint.sql，saver 以 CREATE_NONE 模式使用
     * （构造期不做 DDL，避免与 Flyway 初始化次序冲突）。
     */
    @Bean
    public BaseCheckpointSaver checkpointSaver(javax.sql.DataSource dataSource) {
        return PostgresSaver.builder()
            .datasource(dataSource)
            .createOption(CreateOption.CREATE_NONE)
            .build();
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
