package com.linagent.agent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.linagent.agent.compaction.SummarizingModelHook;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.skills.FilteredSkillRegistry;
import com.linagent.agent.skills.ResidentPromptBuilder;
import com.linagent.agent.stream.ThinkingExtractor;
import com.linagent.agent.stream.ThinkingTapChatModel;
import com.linagent.agent.tools.CsvSummaryTool;
import com.linagent.agent.tools.FileTools;
import com.linagent.agent.workspace.WorkspaceResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每轮对话构建独立 ReactAgent 实例：便于注入该轮的 thinking 旁路 Sink 与该轮身份的
 * 个人工作区——FileTools/ShellTool2/CsvSummaryTool 实例随轮构造，烤入 personalRoot
 * （跨身份零共享，reactor 线程零 ThreadLocal）。saver（记忆）、技能注册表为共享 Bean。
 */
@Component
public class AgentFactory {

    private final ChatModel chatModel;
    private final ThinkingExtractor thinkingExtractor;
    private final FilteredSkillRegistry skillRegistry;
    private final ResidentPromptBuilder residentPromptBuilder;
    private final BaseCheckpointSaver checkpointSaver;
    private final WorkspaceResolver workspaceResolver;
    private final SummarizingModelHook summarizingHook;

    public AgentFactory(ChatModel chatModel, ThinkingExtractor thinkingExtractor,
                        FilteredSkillRegistry skillRegistry,
                        ResidentPromptBuilder residentPromptBuilder, BaseCheckpointSaver checkpointSaver,
                        WorkspaceResolver workspaceResolver, SummarizingModelHook summarizingHook) {
        this.chatModel = chatModel;
        this.thinkingExtractor = thinkingExtractor;
        this.skillRegistry = skillRegistry;
        this.residentPromptBuilder = residentPromptBuilder;
        this.checkpointSaver = checkpointSaver;
        this.workspaceResolver = workspaceResolver;
        this.summarizingHook = summarizingHook;
    }

    /** ReactAgent 不暴露 systemPrompt 读取口（仅 instruction()），装配产物随 handle 携带 */
    public record AgentHandle(ReactAgent agent, ThinkingTapChatModel tappedModel, String systemPrompt) {}

    public AgentHandle create(AuthContext ctx, Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture) {
        return create(ctx, thinkingSink, usageCapture, List.of());
    }

    /** Task 8 消费：注入该轮的 ToolInterceptor（工具调用审计/持久化） */
    public AgentHandle create(AuthContext ctx, Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture,
                              ToolInterceptor toolInterceptor) {
        return create(ctx, thinkingSink, usageCapture, List.of(toolInterceptor));
    }

    private AgentHandle create(AuthContext ctx, Sinks.Many<Object> thinkingSink, AtomicReference<Object> usageCapture,
                               List<Interceptor> interceptors) {
        Path personalRoot = workspaceResolver.personalRoot(ctx.tenantId(), ctx.userId());
        FileTools fileTools = new FileTools(personalRoot);

        ThinkingTapChatModel tapped = new ThinkingTapChatModel(chatModel, thinkingSink, thinkingExtractor, usageCapture);

        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
            .skillRegistry(skillRegistry)
            .autoReload(true)
            .groupedTools(Map.of("csv-analysis",
                List.of(new CsvSummaryTool(fileTools.workspace()).callback())))
            .build();

        ShellToolAgentHook shellHook = ShellToolAgentHook.builder()
            .shellTool2(ShellTool2.builder(personalRoot.toString()).build())
            .build();

        String systemPrompt = residentPromptBuilder.build();

        ReactAgent agent = ReactAgent.builder()
            .name("lin-agent")
            .model(tapped)
            .systemPrompt(systemPrompt)
            .tools(fileTools.toCallbacks())
            .hooks(List.of(skillsHook, shellHook, summarizingHook))
            .saver(checkpointSaver)
            .interceptors(interceptors)
            .build();

        return new AgentHandle(agent, tapped, systemPrompt);
    }
}
