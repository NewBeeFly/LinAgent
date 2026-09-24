package com.linagent.agent.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.linagent.agent.approval.ApprovalHook;
import com.linagent.agent.approval.InMemorySessionRules;
import com.linagent.agent.approval.PermissionRuleEngine;
import com.linagent.agent.compaction.SummarizingModelHook;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.conversation.ChatMode;
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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 每轮对话构建独立 ReactAgent 实例：便于注入该轮的 thinking 旁路 Sink 与该轮身份的
 * 个人工作区——FileTools/ShellTool2/CsvSummaryTool 实例随轮构造，烤入 personalRoot
 * （跨身份零共享，reactor 线程零 ThreadLocal）。saver（记忆）、技能注册表为共享 Bean。
 * 审批 HITL（Task 5）同样随轮装配：PermissionRuleEngine + ApprovalHook 按该轮
 * (tenant, user, conversation) 构造（Task 4 recipe），engine/hook 非 Bean 跨轮零共享；
 * userRules 每轮查库（「批准并记住」的新规则下轮即生效）。
 *
 * <p>v0.3 会话档位（spec §1）：按 {@link ChatMode} 分支装配——STANDARD 现状全挂（含审批）；
 * AUTO 免审批（ApprovalHook 与中断节点注册整体缺席，tools/技能/压缩照常）；CHAT 完全无工具
 * （tools 空列表 + 仅压缩钩子 + prompt 追加 {@link #CHAT_SUFFIX} 声明）。档位由 facade 读
 * conversation.mode 传入，进行中轮次不受切换影响（每轮重新构造）。
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

    /** CHAT（纯聊档）专用精简 system prompt——模板文件 prompts/system-prompt-chat.md
     * （正向声明纯对话、全文无工具说明），经 {@link ResidentPromptBuilder#buildChat()} 缓存加载。 */

    /** ReactAgent 不暴露 systemPrompt 读取口（仅 instruction()），装配产物随 handle 携带；
     *  mode 为构造档位（v0.3 三档断言面，测试/E2E 用） */
    public record AgentHandle(ReactAgent agent, ThinkingTapChatModel tappedModel, String systemPrompt,
                              Consumer<RunnableConfig> resumePrimer, ChatMode mode) {

        /** chat 路径无需预备（BEFORE_AGENT 钩子承担初始化），无操作 primer；档位默认 STANDARD */
        public AgentHandle(ReactAgent agent, ThinkingTapChatModel tappedModel, String systemPrompt) {
            this(agent, tappedModel, systemPrompt, config -> { }, ChatMode.STANDARD);
        }
    }

    public AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                              AtomicReference<Object> usageCapture) {
        return create(ctx, conversationId, thinkingSink, usageCapture, List.of(), ChatMode.STANDARD);
    }

    /** 工具拦截器注入（EventEmittingToolInterceptor：工具事件旁路 + 展示存储落库） */
    public AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                              AtomicReference<Object> usageCapture, ToolInterceptor toolInterceptor) {
        return create(ctx, conversationId, thinkingSink, usageCapture, List.of(toolInterceptor), ChatMode.STANDARD);
    }

    /** v0.3 会话档位入口（spec §2.4 生效链）：facade 读 conv.mode 传入，按档分支装配 */
    public AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                              AtomicReference<Object> usageCapture, ToolInterceptor toolInterceptor,
                              ChatMode mode) {
        return create(ctx, conversationId, thinkingSink, usageCapture, List.of(toolInterceptor), mode);
    }

    /** 同上，无工具拦截器场景（等价空拦截器列表；测试/无事件旁路调用便利） */
    public AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                              AtomicReference<Object> usageCapture, ChatMode mode) {
        return create(ctx, conversationId, thinkingSink, usageCapture, List.of(), mode);
    }

    private AgentHandle create(AuthContext ctx, Long conversationId, Sinks.Many<Object> thinkingSink,
                               AtomicReference<Object> usageCapture,
                               List<Interceptor> interceptors, ChatMode mode) {
        Path personalRoot = workspaceResolver.personalRoot(ctx.tenantId(), ctx.userId());

        ThinkingTapChatModel tapped = new ThinkingTapChatModel(chatModel, thinkingSink, thinkingExtractor, usageCapture);

        String basePrompt = residentPromptBuilder.build();

        // CHAT（纯聊档，spec §2.3）：完全无工具——tools 空列表（SAA hasTools 标志位设计内支持
        // 空列表路由，无工具节点）、不挂 skills/shell/approval 钩子（仅压缩）；
        // 无 shell 钩子即无会话初始化需求，resumePrimer 走无操作缺省。
        // prompt 用专用精简模板（正向声明纯对话、全文无工具说明）——实测通用串+尾部否定
        // 追加压不住历史工具模仿，前文工具说明必须整体移除（ResidentPromptBuilder.buildChat）
        if (mode == ChatMode.CHAT) {
            String chatPrompt = residentPromptBuilder.buildChat();
            ReactAgent agent = ReactAgent.builder()
                .name("lin-agent")
                .model(tapped)
                .systemPrompt(chatPrompt)
                .tools(List.of())
                .hooks(List.of(summarizingHook))
                .saver(checkpointSaver)
                .interceptors(interceptors)
                .build();
            return new AgentHandle(agent, tapped, chatPrompt, config -> { }, mode);
        }

        FileTools fileTools = new FileTools(personalRoot);

        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
            .skillRegistry(skillRegistry)
            .autoReload(true)
            .groupedTools(Map.of("csv-analysis",
                List.of(new CsvSummaryTool(fileTools.workspace()).callback())))
            .build();

        ShellTool2 shellTool = ShellTool2.builder(personalRoot.toString()).build();
        ShellToolAgentHook shellHook = ShellToolAgentHook.builder()
            .shellTool2(shellTool)
            .build();

        // resume 预备（Task 6 E2E 实证）：从 checkpoint 恢复的续跑不经过 BEFORE_AGENT 节点
        // ——ShellToolAgentHook 的会话初始化（session 存 config.context()）不会执行，
        // 续跑首个 shell 调用将报 "Shell session not initialized"。resume 前补一次
        // initialize（幂等起点：该 config 为全新对象，与 chat 轮互不串扰）
        Consumer<RunnableConfig> resumePrimer = config -> shellTool.getSessionManager().initialize(config);

        // AUTO（自由档，spec §1）：免审批——ApprovalHook 不挂、审批中断节点不注册
        // （工具直执行零中断），tools/技能/压缩照常
        if (mode == ChatMode.AUTO) {
            ReactAgent agent = ReactAgent.builder()
                .name("lin-agent")
                .model(tapped)
                .systemPrompt(basePrompt)
                .tools(fileTools.toCallbacks())
                .hooks(List.of(skillsHook, shellHook, summarizingHook))
                .saver(checkpointSaver)
                .interceptors(interceptors)
                .build();
            return new AgentHandle(agent, tapped, basePrompt, resumePrimer, mode);
        }

        // STANDARD（默认档，现状全挂）：审批 HITL 随轮装配（Task 4 recipe）：userRules 按
        // 归属查 ALLOW 规则，session 规则进程内共享（同 InMemorySessionRules Bean），
        // 判定上下文绑定本轮会话
        List<PermissionRule> userRules = permissionRules.findByTenantIdAndUserIdAndEffect(
            ctx.tenantId(), ctx.userId(), "ALLOW");
        ApprovalHook approvalHook = new ApprovalHook(
            new PermissionRuleEngine(userRules, sessionRules),
            new PermissionRuleEngine.ApprovalContext(ctx.tenantId(), ctx.userId(), conversationId));

        ReactAgent agent = ReactAgent.builder()
            .name("lin-agent")
            .model(tapped)
            .systemPrompt(basePrompt)
            .tools(fileTools.toCallbacks())
            .hooks(List.of(skillsHook, shellHook, summarizingHook, approvalHook))
            .saver(checkpointSaver)
            .interceptors(interceptors)
            .build();

        registerInterruptibleApprovalNode(agent, approvalHook);

        return new AgentHandle(agent, tapped, basePrompt, resumePrimer, mode);
    }

    /**
     * SAA 1.1.2.3 实证缺口（Task 6 E2E 首跑暴露，字节码级定位）：ReactAgent.initGraph
     * 组装 AFTER_MODEL 钩子节点时，只对【具体类型】HumanInTheLoopHook（及 BEFORE_MODEL 的
     * InterruptionHook）把 hook 实例本身注册为节点 action——实例实现 InterruptableAction，
     * NodeExecutor 据此在 apply 前调用 interrupt()；其余 ModelHook 一律包一层
     * AsyncNodeActionWithConfig lambda（仅 afterModel），中断能力静默丢失——自定义
     * ApprovalHook 经 .hooks() 挂载后 interrupt() 永不触发，工具不经审批直接执行。
     *
     * <p>HumanInTheLoopHook 构造器 private 无法继承（判定层也无法换成它的静态 approvalOn
     * 名单——白名单/规则引擎的动态判定放不进去），故在图编译后把本钩子节点的
     * ActionFactory 换回 hook 本体：CompiledGraph.getNodeAction 逐次经 nodeFactories
     * 解析（无缓存旁路），节点名/边/checkpoint.nextNodeId 全部不变，仅 action 换为
     * 与 SAA 原生 HITL 同构的实例注册。E2E 守护：ApprovalEndToEndTest。
     */
    private static void registerInterruptibleApprovalNode(ReactAgent agent, ApprovalHook approvalHook) {
        try {
            CompiledGraph graph = agent.getAndCompileGraph();
            Field factoriesField = CompiledGraph.class.getDeclaredField("nodeFactories");
            factoriesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Node.ActionFactory> factories =
                (Map<String, Node.ActionFactory>) factoriesField.get(graph);
            // 节点名 = getFullHookName(hook) + 位置后缀（实测 ".afterModel"）；按前缀定位，
            // 不硬编码后缀（SAA 版本升级后缀变化时前缀仍稳定）
            String namePrefix = Hook.getFullHookName(approvalHook);
            String nodeKey = factories.keySet().stream()
                .filter(key -> key.startsWith(namePrefix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "审批钩子节点未注册（预期前缀 " + namePrefix + "）：SAA 图结构变化，需重新适配"));
            Map<String, Node.ActionFactory> writable = factories;
            try {
                factories.put(nodeKey, config -> approvalHook);
            }
            catch (UnsupportedOperationException immutableMap) {
                writable = new HashMap<>(factories);
                writable.put(nodeKey, config -> approvalHook);
                factoriesField.set(graph, writable);
            }
        }
        catch (ReflectiveOperationException e) {
            // 静默降级 = 审批机制整体失效（工具不经审批直接执行），必须响亮失败
            throw new IllegalStateException("审批钩子中断能力注册失败：CompiledGraph 结构与预期不符", e);
        }
    }
}
