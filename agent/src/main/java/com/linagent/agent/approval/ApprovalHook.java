package com.linagent.agent.approval;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.InterruptableAction;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.state.RemoveByHash;
import com.alibaba.cloud.ai.graph.utils.TypeRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 工具审批 HITL hook：骨架（interrupt/afterModel/getLastAssistantMessage/REJECTED 双保险语义）
 * 照抄 SAA {@code HumanInTheLoopHook}，判定层由静态 approvalOn 名单替换为
 * {@link PermissionRuleEngine}（内置白名单 → session → user → 审批，spec §5）。
 *
 * <p>与 SAA 原版的两处差异：
 * <ul>
 * <li>中断构建（{@link #buildInterruptionMetadata}）：逐 toolCall 解析 arguments JSON 提取
 * payload（shell=command / write_file=path，字段名映射常量）交引擎判定；
 * {@code PendingItem.arguments} 覆写为<b>原始 arguments JSON</b>（引擎只知道提取后的 payload）；
 * 全部待审批项列表存入 metadata（{@link #PENDING_ITEMS_METADATA_KEY}）供 {@link #verdictFrom} 反解。</li>
 * <li>feedback 校验（{@link #validateFeedback}）：「哪些调用需要审批」同样按引擎逐次判定，
 * 而非静态名单——resume 时若决策方已补 session 规则，该项即不再需要审批，校验自然通过。</li>
 * </ul>
 *
 * <p>afterModel 的 APPROVED/EDITED/REJECTED 三分支照抄 SAA 未改：REJECTED 的 toolCall 保留在
 * 替换后的 AssistantMessage 并追加同 id 拒绝 ToolResponse（spike 实测：路由层据此跳过工具节点，
 * 双保险不执行）。
 *
 * <p>不可变（engine/context 均 final），线程安全；engine 每轮随 userRules 新建，故本 hook
 * 也应随轮构造（AgentFactory），不注册为 Bean。
 */
@HookPositions(HookPosition.AFTER_MODEL)
public class ApprovalHook extends ModelHook implements AsyncNodeActionWithConfig, InterruptableAction {

    private static final Logger log = LoggerFactory.getLogger(ApprovalHook.class);

    /** 图节点名（与 SAA HITL 一致，拼前缀后为 _AGENT_HOOK_HITL，checkpoint nextNodeId 依赖） */
    public static final String HITL_NODE_NAME = "HITL";

    /**
     * InterruptionMetadata.metadata 下挂全部待审批项（List&lt;PendingItem&gt;）的 key，
     * {@link #verdictFrom} 从此反解供 facade 构建 ApprovalRequest 事件。
     */
    public static final String PENDING_ITEMS_METADATA_KEY = "approval.pendingItems";

    /** payload 提取的字段名映射（工具参数 JSON → 参与判定的命令/路径字段） */
    private static final Map<String, String> PAYLOAD_ARG_BY_TOOL = Map.of(
            "shell", "command",
            "write_file", "path");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PermissionRuleEngine engine;
    private final PermissionRuleEngine.ApprovalContext context;

    public ApprovalHook(PermissionRuleEngine engine, PermissionRuleEngine.ApprovalContext context) {
        this.engine = engine;
        this.context = context;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        return afterModel(state, config);
    }

    // —— afterModel：SAA 原生 feedback 处理，照抄未改 ——

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        Optional<InterruptionMetadata> feedback = config.getMetadataAndRemove(
                RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, new TypeRef<InterruptionMetadata>() { });
        InterruptionMetadata interruptionMetadata = feedback.orElse(null);

        if (interruptionMetadata == null) {
            log.debug("No human feedback found in the runnable config metadata, no tool to execute or none needs feedback.");
            return CompletableFuture.completedFuture(Map.of());
        }

        AssistantMessage assistantMessage = getLastAssistantMessage(state);

        if (assistantMessage != null) {

            if (!assistantMessage.hasToolCalls()) {
                log.info("Found human feedback but last AssistantMessage has no tool calls, nothing to process for human feedback.");
                return CompletableFuture.completedFuture(Map.of());
            }

            List<AssistantMessage.ToolCall> newToolCalls = new ArrayList<>();

            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            ToolResponseMessage rejectedMessage = ToolResponseMessage.builder().responses(responses).build();

            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                Optional<ToolFeedback> toolFeedbackOpt = interruptionMetadata.toolFeedbacks().stream()
                        .filter(tf -> tf.getId().equals(toolCall.id()))
                        .findFirst();

                if (toolFeedbackOpt.isPresent()) {
                    ToolFeedback toolFeedback = toolFeedbackOpt.get();
                    FeedbackResult result = toolFeedback.getResult();

                    if (result == FeedbackResult.APPROVED) {
                        newToolCalls.add(toolCall);
                    }
                    else if (result == FeedbackResult.EDITED) {
                        AssistantMessage.ToolCall editedToolCall = new AssistantMessage.ToolCall(
                                toolCall.id(), toolCall.type(), toolCall.name(), toolFeedback.getArguments());
                        newToolCalls.add(editedToolCall);
                    }
                    else if (result == FeedbackResult.REJECTED) {
                        newToolCalls.add(toolCall);
                        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                                toolCall.id(), toolCall.name(),
                                String.format("Tool call request for %s has been rejected by human. "
                                                + "The reason for why this tool is rejected and the suggestion "
                                                + "for next possible tool choose is listed as below:\n %s.",
                                        toolFeedback.getName(), toolFeedback.getDescription()));
                        responses.add(response);
                    }
                }
                else {
                    // If no feedback is provided for a tool that requires approval, treat it as approved to continue.
                    newToolCalls.add(toolCall);
                }
            }

            Map<String, Object> updates = new HashMap<>();
            List<Object> newMessages = new ArrayList<>();

            if (!newToolCalls.isEmpty()) {
                // Replace the last message with the new assistant message containing updated tool calls
                newMessages.add(AssistantMessage.builder()
                        .content(assistantMessage.getText())
                        .properties(assistantMessage.getMetadata())
                        .toolCalls(newToolCalls)
                        .media(assistantMessage.getMedia())
                        .build());
                newMessages.add(new RemoveByHash<>(assistantMessage));
            }

            // ToolResponseMessages must be added after AssistantMessage
            if (!rejectedMessage.getResponses().isEmpty()) {
                newMessages.add(rejectedMessage);
            }

            updates.put("messages", newMessages);
            return CompletableFuture.completedFuture(updates);
        }
        else {
            log.warn("Last message is not an AssistantMessage, cannot process human feedback.");
        }

        return CompletableFuture.completedFuture(Map.of());
    }

    // —— interrupt：SAA 结构照抄，判定层换引擎 ——

    @Override
    public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
        AssistantMessage lastMessage = getLastAssistantMessage(state);

        if (lastMessage == null || !lastMessage.hasToolCalls()) {
            return Optional.empty();
        }

        Optional<Object> feedback = config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY);
        if (feedback.isPresent()) {
            if (!(feedback.get() instanceof InterruptionMetadata)) {
                throw new IllegalArgumentException("Human feedback metadata must be of type InterruptionMetadata.");
            }

            if (!validateFeedback((InterruptionMetadata) feedback.get(), lastMessage.getToolCalls())) {
                return buildInterruptionMetadata(state, lastMessage);
            }
            return Optional.empty();
        }

        // 2. If last message is AssistantMessage
        return buildInterruptionMetadata(state, lastMessage);
    }

    /** SAA 骨架照抄（含「后跟 ToolResponseMessage 即工具已执行、不再中断」防重逻辑） */
    private static AssistantMessage getLastAssistantMessage(OverAllState state) {
        List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());

        AssistantMessage lastMessage = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage assistantMessage) {
                // If the next element (i+1) is a ToolResponseMessage, return empty(tools already executed)
                if (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                    break;
                }
                // If the next element is not a ToolResponseMessage, assign and continue
                lastMessage = assistantMessage;
                break;
            }
        }
        return lastMessage;
    }

    /**
     * 中断构建（替换 SAA 的 approvalOn 名单判定）：逐 toolCall 提取 payload → 引擎判定；
     * 任一 NEEDS_APPROVAL 即中断，该项进 toolFeedbacks（description 为引擎明细渲染文本），
     * 放行项进 toolsAutomaticallyApproved；全部待审批项存 metadata 供 {@link #verdictFrom}。
     */
    private Optional<InterruptionMetadata> buildInterruptionMetadata(OverAllState state, AssistantMessage lastMessage) {
        boolean needsInterruption = false;
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder(Hook.getFullHookName(this), state);
        List<PermissionRuleEngine.PendingItem> pendingItems = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : lastMessage.getToolCalls()) {
            PermissionRuleEngine.Verdict verdict = evaluate(toolCall);
            if (verdict.needsApproval()) {
                // arguments 覆写为原始 JSON：引擎侧与 payload 同值（引擎只见提取后的命令/路径）
                PermissionRuleEngine.PendingItem item = verdict.items().get(0);
                item = new PermissionRuleEngine.PendingItem(item.callId(), item.toolName(),
                        toolCall.arguments(), item.payload(), item.subVerdicts(), item.suggestedRule());
                pendingItems.add(item);
                builder.addToolFeedback(ToolFeedback.builder()
                                .id(toolCall.id()).name(toolCall.name())
                                .description(renderDescription(item)).arguments(toolCall.arguments()).build());
                needsInterruption = true;
            }
            else {
                builder.addToolsAutomaticallyApproved(toolCall);
            }
        }
        if (needsInterruption) {
            builder.addMetadata(PENDING_ITEMS_METADATA_KEY, List.copyOf(pendingItems));
            return Optional.of(builder.build());
        }
        return Optional.empty();
    }

    /** feedback 校验（SAA 骨架，「需要审批的调用」改为按引擎逐次判定） */
    private boolean validateFeedback(InterruptionMetadata feedback, List<AssistantMessage.ToolCall> toolCalls) {
        if (feedback == null || feedback.toolFeedbacks() == null || feedback.toolFeedbacks().isEmpty()) {
            return false;
        }

        List<ToolFeedback> toolFeedbacks = feedback.toolFeedbacks();

        // 1. Tool calls in this step that actually require human approval (per rule engine)
        List<AssistantMessage.ToolCall> toolCallsNeedingApproval = toolCalls.stream()
                .filter(this::needsApproval)
                .toList();

        // If no tool calls in this step require human approval, validation is trivially satisfied
        if (toolCallsNeedingApproval.isEmpty()) {
            return true;
        }

        // 2. For each tool call requiring approval, ensure corresponding feedback exists and its result is non-null
        for (AssistantMessage.ToolCall call : toolCallsNeedingApproval) {
            ToolFeedback matchedFeedback = toolFeedbacks.stream()
                    .filter(tf -> tf.getName().equals(call.name())
                            && call.id().equals(tf.getId()))
                    .findFirst()
                    .orElse(null);

            if (matchedFeedback == null) {
                log.warn("Missing feedback for tool {} (id={}); waiting for human input.",
                        call.name(), call.id());
                return false;
            }

            if (matchedFeedback.getResult() == null) {
                log.warn("Feedback result for tool {} (id={}) is null; waiting for human input.",
                        call.name(), call.id());
                return false;
            }
        }

        // 3. Log unexpected feedback entries that do not match any pending approval tool
        for (ToolFeedback tf : toolFeedbacks) {
            boolean matched = toolCallsNeedingApproval.stream()
                    .anyMatch(call -> call.name().equals(tf.getName()) && call.id().equals(tf.getId()));
            if (!matched) {
                log.warn("Ignoring unexpected tool feedback: name={}, id={}", tf.getName(), tf.getId());
            }
        }

        return true;
    }

    private boolean needsApproval(AssistantMessage.ToolCall toolCall) {
        return evaluate(toolCall).needsApproval();
    }

    private PermissionRuleEngine.Verdict evaluate(AssistantMessage.ToolCall toolCall) {
        String payload = extractPayload(toolCall.name(), toolCall.arguments());
        return engine.evaluate(toolCall.name(), toolCall.id(), payload, context);
    }

    /**
     * 解析 toolCall 的 arguments JSON 提取参与判定的 payload：按工具名映射字段
     * （shell=command / write_file=path）优先，无映射或映射字段缺失时取第一个字符串字段
     * （未注册新工具的兜底，判定走引擎默认分支）。JSON 不合法/无字符串字段 → null
     * （空 payload 语义由引擎裁决；工具执行侧同样解析不了该参数，无越权面）。
     */
    static String extractPayload(String toolName, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(argumentsJson);
            if (!root.isObject()) {
                return null;
            }
            String mappedArg = PAYLOAD_ARG_BY_TOOL.get(toolName);
            if (mappedArg != null) {
                JsonNode mapped = root.get(mappedArg);
                if (mapped != null && mapped.isTextual()) {
                    return mapped.asText();
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                if (value.isTextual()) {
                    return value.asText();
                }
            }
            return null;
        }
        catch (Exception e) {
            log.debug("Cannot parse tool arguments as JSON for payload extraction: tool={}", toolName, e);
            return null;
        }
    }

    /** ToolFeedback.description：引擎明细渲染文本（工具/原始参数/逐段判定/建议规则/审批问句） */
    static String renderDescription(PermissionRuleEngine.PendingItem item) {
        StringBuilder sb = new StringBuilder("The AI is requesting to use the tool: ")
                .append(item.toolName()).append('\n')
                .append("Arguments: ").append(item.arguments()).append('\n')
                .append("Decisions:\n");
        for (PermissionRuleEngine.SubVerdict sv : item.subVerdicts()) {
            sb.append("  - '").append(sv.segment()).append("': ");
            if (sv.allowed()) {
                sb.append("allowed by ").append(sv.source());
            }
            else {
                sb.append("not covered by any rule");
            }
            sb.append('\n');
        }
        sb.append("Suggested rule: ").append(item.suggestedRule()).append('\n');
        sb.append("Do you approve?");
        return sb.toString();
    }

    /**
     * 从中断 metadata 反解全部待审批项（Task 5 facade 构建 ApprovalRequest 事件的输入）。
     * 非 ApprovalHook 产生（无 {@link #PENDING_ITEMS_METADATA_KEY}）的 metadata → 空判定。
     */
    @SuppressWarnings("unchecked")
    public static PermissionRuleEngine.Verdict verdictFrom(InterruptionMetadata interruptionMetadata) {
        Object pending = interruptionMetadata.metadata(PENDING_ITEMS_METADATA_KEY).orElse(null);
        if (pending instanceof List<?> items && !items.isEmpty()) {
            return new PermissionRuleEngine.Verdict(true, (List<PermissionRuleEngine.PendingItem>) items);
        }
        return new PermissionRuleEngine.Verdict(false, List.of());
    }

    @Override
    public String getName() {
        return HITL_NODE_NAME;
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of();
    }
}
