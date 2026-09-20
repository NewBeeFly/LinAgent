package com.javaagent.agent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.javaagent.agent.skills.FilteredSkillRegistry;
import com.javaagent.agent.skills.ResidentPromptBuilder;
import com.javaagent.agent.stream.ThinkingExtractor;
import com.javaagent.agent.stream.ThinkingTapChatModel;
import com.javaagent.agent.tools.CsvSummaryTool;
import com.javaagent.agent.tools.FileTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每轮对话构建独立 ReactAgent 实例：便于注入该轮的 thinking 旁路 Sink。
 * saver（记忆）、技能注册表、工具均为共享 Bean，跨轮生效。
 */
@Component
public class AgentFactory {

    private final ChatModel chatModel;
    private final ThinkingExtractor thinkingExtractor;
    private final FileTools fileTools;
    private final FilteredSkillRegistry skillRegistry;
    private final ResidentPromptBuilder residentPromptBuilder;
    private final BaseCheckpointSaver checkpointSaver;
    private final String workspaceRoot;

    public AgentFactory(ChatModel chatModel, ThinkingExtractor thinkingExtractor,
                        FileTools fileTools, FilteredSkillRegistry skillRegistry,
                        ResidentPromptBuilder residentPromptBuilder, BaseCheckpointSaver checkpointSaver,
                        @Value("${agent.workspace-root:./workspace}") String workspaceRoot) {
        this.chatModel = chatModel;
        this.thinkingExtractor = thinkingExtractor;
        this.fileTools = fileTools;
        this.skillRegistry = skillRegistry;
        this.residentPromptBuilder = residentPromptBuilder;
        this.checkpointSaver = checkpointSaver;
        this.workspaceRoot = workspaceRoot;
    }

    /** ReactAgent 不暴露 systemPrompt 读取口（仅 instruction()），装配产物随 handle 携带 */
    public record AgentHandle(ReactAgent agent, ThinkingTapChatModel tappedModel, String systemPrompt) {}

    public AgentHandle create(Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture) {
        return create(thinkingSink, usageCapture, List.of());
    }

    /** Task 8 消费：注入该轮的 ToolInterceptor（工具调用审计/持久化） */
    public AgentHandle create(Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture,
                              ToolInterceptor toolInterceptor) {
        return create(thinkingSink, usageCapture, List.of(toolInterceptor));
    }

    private AgentHandle create(Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture,
                               List<Interceptor> interceptors) {
        ThinkingTapChatModel tapped = new ThinkingTapChatModel(chatModel, thinkingSink, thinkingExtractor, usageCapture);

        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
            .skillRegistry(skillRegistry)
            .autoReload(true)
            .groupedTools(Map.of("csv-analysis",
                List.of(new CsvSummaryTool(fileTools.workspace()).callback())))
            .build();

        ShellToolAgentHook shellHook = ShellToolAgentHook.builder()
            .shellTool2(ShellTool2.builder(workspaceRoot).build())
            .build();

        String systemPrompt = residentPromptBuilder.build();

        ReactAgent agent = ReactAgent.builder()
            .name("java-agent")
            .model(tapped)
            .systemPrompt(systemPrompt)
            .tools(fileTools.toCallbacks())
            .hooks(List.of(skillsHook, shellHook))
            .saver(checkpointSaver)
            .interceptors(interceptors)
            .build();

        return new AgentHandle(agent, tapped, systemPrompt);
    }
}
