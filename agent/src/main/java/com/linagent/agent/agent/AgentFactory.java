package com.linagent.agent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.linagent.agent.approval.ApprovalHook;
import com.linagent.agent.approval.InMemorySessionRules;
import com.linagent.agent.approval.PermissionRuleEngine;
import com.linagent.agent.compaction.SummarizingModelHook;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.persistence.po.PermissionRule;
import com.linagent.agent.persistence.repository.PermissionRuleRepository;
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
 * 审批 HITL（Task 5）同样随轮装配：PermissionRuleEngine + ApprovalHook 按该轮
 * (tenant, user, conversation) 构造（Task 4 recipe），engine/hook 非 Bean 跨轮零共享；
 * userRules 每轮查库（「批准并记住」的新规则下轮即生效）。
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
    private final PermissionRuleRepository permissionRules;
    private final InMemorySessionRules sessionRules;

    public AgentFactory(ChatModel chatModel, ThinkingExtractor thinkingExtractor,
                        FilteredSkillRegistry skillRegistry,
                        ResidentPromptBuilder residentPromptBuilder, BaseCheckpointSaver checkpointSaver,
                        WorkspaceResolver workspaceResolver, SummarizingModelHook summarizingHook,
                        PermissionRuleRepository permissionRules, InMemorySessionRules sessionRules) {
        this.chatModel = chatModel;
        this.thinkingExtractor = thinkingExtractor;
        this.skillRegistry = skillRegistry;
        this.residentPromptBuilder = residentPromptBuilder;
        this.checkpointSaver = checkpointSaver;
        this.workspaceResolver = workspaceResolver;
        this.summarizingHook = summarizingHook;
        this.permissionRules = permissionRules;
        this.sessionRules = sessionRules;
    }

    /** ReactAgent 不暴露 systemPrompt 读取口（仅 instruction()），装配产物随 handle 携带 */
    public record AgentHandle(ReactAgent agent, ThinkingTapChatModel tappedModel, String systemPrompt) {}

    public AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                              AtomicReference<Object> usageCapture) {
        return create(ctx, conversationId, thinkingSink, usageCapture, List.of());
    }

    /** 工具拦截器注入（EventEmittingToolInterceptor：工具事件旁路 + 展示存储落库） */
    public AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                              AtomicReference<Object> usageCapture, ToolInterceptor toolInterceptor) {
        return create(ctx, conversationId, thinkingSink, usageCapture, List.of(toolInterceptor));
    }

    private AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                               AtomicReference<Object> usageCapture,
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

        // 审批 HITL 随轮装配（Task 4 recipe）：userRules 按归属查 ALLOW 规则，
        // session 规则进程内共享（同 InMemorySessionRules Bean），判定上下文绑定本轮会话
        List<PermissionRule> userRules = permissionRules.findByTenantIdAndUserIdAndEffect(
            ctx.tenantId(), ctx.userId(), "ALLOW");
        ApprovalHook approvalHook = new ApprovalHook(
            new PermissionRuleEngine(userRules, sessionRules),
            new PermissionRuleEngine.ApprovalContext(ctx.tenantId(), ctx.userId(), conversationId));

        String systemPrompt = residentPromptBuilder.build();

        ReactAgent agent = ReactAgent.builder()
            .name("lin-agent")
            .model(tapped)
            .systemPrompt(systemPrompt)
            .tools(fileTools.toCallbacks())
            .hooks(List.of(skillsHook, shellHook, summarizingHook, approvalHook))
            .saver(checkpointSaver)
            .interceptors(interceptors)
            .build();

        return new AgentHandle(agent, tapped, systemPrompt);
    }
}
