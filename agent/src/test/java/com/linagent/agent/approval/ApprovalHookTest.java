package com.linagent.agent.approval;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.state.RemoveByHash;
import com.linagent.agent.approval.PermissionRuleEngine.ApprovalContext;
import com.linagent.agent.approval.PermissionRuleEngine.PendingItem;
import com.linagent.agent.approval.PermissionRuleEngine.SubVerdict;
import com.linagent.agent.approval.PermissionRuleEngine.Verdict;
import com.linagent.agent.persistence.po.PermissionRule;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ApprovalHook 单元矩阵（task-4 brief Step 1 逐条对照）：
 * interrupt 判定（白名单/无 toolCalls → empty；非白名单 shell → metadata 携带引擎明细；
 * 混合调用拆分 feedback/automaticallyApproved；resume feedback 校验）
 * / afterModel 三分支（APPROVED 替换保留 toolCalls、EDITED 换参、REJECTED 拒绝 ToolResponse，
 * 照抄 SAA 骨架验证）/ verdictFrom 反解 round-trip（arguments 必须是原始 JSON 而非提取后 payload）。
 */
class ApprovalHookTest {

    private static final ApprovalContext CTX = new ApprovalContext("default", "linmj", 42L);

    private static ApprovalHook hook(List<PermissionRule> userRules) {
        return new ApprovalHook(new PermissionRuleEngine(userRules, new InMemorySessionRules()), CTX);
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static AssistantMessage assistant(AssistantMessage.ToolCall... calls) {
        return AssistantMessage.builder().content("").properties(Map.of()).toolCalls(List.of(calls)).build();
    }

    private static OverAllState state(Message... messages) {
        return new OverAllState(Map.of("messages", new ArrayList<>(List.of(messages))));
    }

    private static RunnableConfig plainConfig() {
        return RunnableConfig.builder().threadId("t").build();
    }

    /** resume 用 feedback metadata：result 待定的待审批形态（APPROVED/REJECTED 由调用方指定） */
    private static InterruptionMetadata feedbackMetadata(ToolFeedback.FeedbackResult result,
                                                         String description, String arguments) {
        return InterruptionMetadata.builder("test", null)
                .addToolFeedback(ToolFeedback.builder()
                        .id("c1").name("shell").arguments(arguments)
                        .result(result).description(description).build())
                .build();
    }

    // ── interrupt 判定 ──

    @Test
    void interruptSkipsWhenNothingNeedsApproval() {
        ApprovalHook hook = hook(List.of());

        // 无 toolCalls
        assertThat(hook.interrupt("node", state(assistant()), plainConfig())).isEmpty();
        // 白名单 shell 命令（BUILTIN 放行）
        assertThat(hook.interrupt("node", state(assistant(
                call("c1", "shell", "{\"command\":\"ls -la\"}"))), plainConfig())).isEmpty();
        // read 类工具恒放行
        assertThat(hook.interrupt("node", state(assistant(
                call("c1", "read_file", "{\"path\":\"a.csv\"}"))), plainConfig())).isEmpty();
        // 防重：AssistantMessage 后已跟 ToolResponseMessage（工具已执行）→ 不中断
        AssistantMessage executed = assistant(call("c1", "shell", "{\"command\":\"rm x\"}"));
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "shell", "done"))).build();
        assertThat(hook.interrupt("node", state(executed, response), plainConfig())).isEmpty();
    }

    @Test
    void interruptFlagsUncoveredShellWithEngineDetail() {
        ApprovalHook hook = hook(List.of());
        String rawArguments = "{\"command\":\"git status && rm -rf /tmp/x\"}";

        var md = hook.interrupt("node", state(assistant(call("c1", "shell", rawArguments))), plainConfig());

        assertThat(md).isPresent();
        assertThat(md.get().node()).isEqualTo("_AGENT_HOOK_HITL");
        assertThat(md.get().toolFeedbacks()).hasSize(1);
        ToolFeedback feedback = md.get().toolFeedbacks().get(0);
        assertThat(feedback.getId()).isEqualTo("c1");
        assertThat(feedback.getName()).isEqualTo("shell");
        assertThat(feedback.getResult()).isNull();
        // arguments 携带原始 JSON（非提取后的 command 值）
        assertThat(feedback.getArguments()).isEqualTo(rawArguments);
        assertThat(md.get().getToolsAutomaticallyApproved()).isEmpty();
        // description = 引擎明细渲染文本：含各段判定与建议规则
        assertThat(feedback.getDescription())
                .contains("git status")
                .contains("BUILTIN")
                .contains("rm -rf /tmp/x")
                .contains("not covered by any rule")
                .contains("Suggested rule: git status *");
    }

    @Test
    void interruptSplitsMixedCallsIntoFeedbackAndAutoApproved() {
        ApprovalHook hook = hook(List.of());
        AssistantMessage.ToolCall read = call("c-read", "read_file", "{\"path\":\"a.csv\"}");
        AssistantMessage.ToolCall shell = call("c-shell", "shell", "{\"command\":\"rm x\"}");
        // write_file：字段序反转（content 在前），payload 必须按字段名映射取 path 而非首个字符串字段
        AssistantMessage.ToolCall write = call("c-write", "write_file",
                "{\"content\":\"hello\",\"path\":\"reports/a.csv\"}");

        var md = hook.interrupt("node", state(assistant(read, shell, write)), plainConfig());

        assertThat(md).isPresent();
        assertThat(md.get().toolFeedbacks()).hasSize(2);
        assertThat(md.get().toolFeedbacks()).extracting(ToolFeedback::getId)
                .containsExactly("c-shell", "c-write");
        assertThat(md.get().getToolsAutomaticallyApproved()).hasSize(1);
        assertThat(md.get().getToolsAutomaticallyApproved().get(0).name()).isEqualTo("read_file");
    }

    @Test
    void interruptValidatesResumeFeedbackViaEngine() {
        ApprovalHook hook = hook(List.of());
        OverAllState state = state(assistant(call("c1", "shell", "{\"command\":\"rm x\"}")));

        // 完整 feedback（result 非空且 id/name 匹配待审批项）→ 放行不中断
        RunnableConfig approved = RunnableConfig.builder().threadId("t")
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                        feedbackMetadata(ToolFeedback.FeedbackResult.APPROVED, null, "{\"command\":\"rm x\"}"))
                .build();
        assertThat(hook.interrupt("node", state, approved)).isEmpty();

        // result 未定（待审批形态回放）→ 重新中断
        RunnableConfig pending = RunnableConfig.builder().threadId("t")
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                        feedbackMetadata(null, "why", "{\"command\":\"rm x\"}"))
                .build();
        assertThat(hook.interrupt("node", state, pending)).isPresent();

        // 非 InterruptionMetadata 类型的 feedback 值 → 拒绝（SAA 契约照抄）
        RunnableConfig bogus = RunnableConfig.builder().threadId("t")
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, "placeholder")
                .build();
        assertThatThrownBy(() -> hook.interrupt("node", state, bogus))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── afterModel：SAA feedback 处理骨架照抄验证 ──

    @Test
    void afterModelApprovedReplacesAssistantKeepingToolCalls() {
        ApprovalHook hook = hook(List.of());
        AssistantMessage.ToolCall original = call("c1", "shell", "{\"command\":\"rm x\"}");
        RunnableConfig config = RunnableConfig.builder().threadId("t")
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                        feedbackMetadata(ToolFeedback.FeedbackResult.APPROVED, null, "{\"command\":\"rm x\"}"))
                .build();

        Map<String, Object> updates = hook.afterModel(state(assistant(original)), config).join();

        List<Object> messages = (List<Object>) updates.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) messages.get(0)).getToolCalls())
                .containsExactly(original);
        assertThat(messages.get(1)).isInstanceOf(RemoveByHash.class);
    }

    @Test
    void afterModelEditedSwapsArguments() {
        ApprovalHook hook = hook(List.of());
        RunnableConfig config = RunnableConfig.builder().threadId("t")
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                        feedbackMetadata(ToolFeedback.FeedbackResult.EDITED, null, "{\"command\":\"ls -la\"}"))
                .build();

        Map<String, Object> updates = hook.afterModel(
                state(assistant(call("c1", "shell", "{\"command\":\"rm x\"}"))), config).join();

        AssistantMessage rebuilt = (AssistantMessage) ((List<Object>) updates.get("messages")).get(0);
        assertThat(rebuilt.getToolCalls()).hasSize(1);
        assertThat(rebuilt.getToolCalls().get(0).id()).isEqualTo("c1");
        assertThat(rebuilt.getToolCalls().get(0).arguments()).isEqualTo("{\"command\":\"ls -la\"}");
    }

    @Test
    void afterModelRejectedKeepsCallAndFeedsRejectionResponse() {
        ApprovalHook hook = hook(List.of());
        RunnableConfig config = RunnableConfig.builder().threadId("t")
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                        feedbackMetadata(ToolFeedback.FeedbackResult.REJECTED, "dangerous op", "{\"command\":\"rm x\"}"))
                .build();

        Map<String, Object> updates = hook.afterModel(
                state(assistant(call("c1", "shell", "{\"command\":\"rm x\"}"))), config).join();

        List<Object> messages = (List<Object>) updates.get("messages");
        assertThat(messages).hasSize(3);
        // REJECTED 的 toolCall 保留在替换后的 AssistantMessage（SAA 双保险语义，spike 实测）
        assertThat(((AssistantMessage) messages.get(0)).getToolCalls()).hasSize(1);
        assertThat(messages.get(1)).isInstanceOf(RemoveByHash.class);
        ToolResponseMessage rejection = (ToolResponseMessage) messages.get(2);
        assertThat(rejection.getResponses()).hasSize(1);
        assertThat(rejection.getResponses().get(0).responseData())
                .contains("rejected by human")
                .contains("dangerous op");
    }

    @Test
    void afterModelWithoutFeedbackMetadataYieldsNoUpdates() {
        Map<String, Object> updates = hook(List.of())
                .afterModel(state(assistant(call("c1", "shell", "{\"command\":\"rm x\"}"))), plainConfig())
                .join();
        assertThat(updates).isEmpty();
    }

    // ── verdictFrom 反解（Task 5 facade 构建 ApprovalRequest 事件的输入）──

    @Test
    void verdictFromRestoresPendingItemsRoundTrip() {
        ApprovalHook hook = hook(List.of());
        String rawArguments = "{\"command\":\"git status && rm -rf /tmp/x\"}";
        var md = hook.interrupt("node", state(assistant(call("c1", "shell", rawArguments))), plainConfig());

        Verdict verdict = ApprovalHook.verdictFrom(md.orElseThrow());

        assertThat(verdict.needsApproval()).isTrue();
        assertThat(verdict.items()).containsExactly(new PendingItem(
                "c1", "shell", rawArguments, "git status && rm -rf /tmp/x",
                List.of(new SubVerdict("git status", true, "BUILTIN"),
                        new SubVerdict("rm -rf /tmp/x", false, null)),
                "git status *"));
    }

    @Test
    void verdictFromExtractsMappedPayloadFieldForWriteFile() {
        ApprovalHook hook = hook(List.of());
        var md = hook.interrupt("node", state(assistant(
                call("c-write", "write_file", "{\"content\":\"hello\",\"path\":\"reports/a.csv\"}"))),
                plainConfig());

        PendingItem item = ApprovalHook.verdictFrom(md.orElseThrow()).items().get(0);

        // payload 按字段名映射取 path（content 在 JSON 里排前也不受影响）
        assertThat(item.payload()).isEqualTo("reports/a.csv");
        assertThat(item.arguments()).isEqualTo("{\"content\":\"hello\",\"path\":\"reports/a.csv\"}");
        assertThat(item.suggestedRule()).isEqualTo("reports/*");
    }

    @Test
    void verdictFromToleratesMetadataWithoutPendingItems() {
        InterruptionMetadata foreign = InterruptionMetadata.builder("test", null).build();

        Verdict verdict = ApprovalHook.verdictFrom(foreign);

        assertThat(verdict.needsApproval()).isFalse();
        assertThat(verdict.items()).isEmpty();
    }
}
