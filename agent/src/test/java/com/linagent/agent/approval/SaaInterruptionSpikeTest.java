package com.linagent.agent.approval;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

/**
 * SPIKE：钉死 SAA 1.1.2.3 中断/恢复三件事（结论写回本注释，Task 4/5/6 依据）：
 * A. 中断时 Flux&lt;NodeOutput&gt; 的信号形态（最后一个 NodeOutput 是什么？流如何 complete？）
 * B. resume 准确方式：同 threadId + addMetadata(HUMAN_FEEDBACK_METADATA_KEY, ...) + stream(?, config)
 * C. REJECTED 的 toolCall 是否真实执行（hook afterModel 把 REJECTED 也保留在 newToolCalls——
 *    工具节点是否会因已有 ToolResponse 而跳过？实测断言见 rejectPath）
 *
 * 【实测结论（2026-09-23，SAA 1.1.2.3）】
 * A: 流正常 onComplete（非 error/非 cancel）。最后一个元素是 {@link InterruptionMetadata}
 *    （它 extends NodeOutput；由 NodeExecutor 发 GraphResponse.done(metadata)，CompiledGraph
 *    streamFromInitialNode 见 resultValue instanceof NodeOutput 而原样透出）。中断实例的
 *    node() == "_AGENT_HOOK_HITL"（HITL 是独立图节点 _AGENT_HOOK_HITL.afterModel，interrupt()
 *    在该节点 apply 前被检查），toolFeedbacks() 携带待审批 ToolFeedback（result==null、
 *    description=hook 拼的 "The AI is requesting to use the tool: ... Do you approve?"）。
 *    其前一元素是模型节点的 StreamingOutput；InterruptionMetadata.state() 携带中断点
 *    状态快照（messages 含待审批 toolCall，Task 4 可直接从中取审批上下文）。
 * B: 同 threadId + RunnableConfig.builder().threadId(tid)
 *    .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback) 且第一参传
 *    Map.of()（空 inputs）——agent.stream(Map.of(), resumeConfig)。GraphRunnerContext 构造时
 *    只要 metadata 里存在 HUMAN_FEEDBACK 键（值不参与该判断）即走 initializeFromResume：
 *    取该 thread 最新 checkpoint，从 checkpoint.nextNodeId（HITL 节点）继续；HITL.interrupt()
 *    校验 feedback（按 name+id 匹配、result 非空）通过后放行，afterModel 消费 feedback 改写消息。
 *    注意：HITL.interrupt 要求该值必须是 InterruptionMetadata 实例，否则抛
 *    IllegalArgumentException（"placeholder" 字符串只适用于无 HITL 的通用 resume）。
 *    feedback 里每个 ToolFeedback.id 必须与待审批 toolCall.id 逐一相等（validateFeedback 按
 *    name+id 双匹配）。第一参传 UserMessage 也能跑，但幻影消息会被拼进历史且插在
 *    原始用户消息之前（乱序，见 resumeWithUserMessagePollutesHistory）——生产用 Map.of()。
 * C: REJECTED 不执行。hook 把 REJECTED 的 toolCall 保留在 newToolCalls 并追加同 id 的拒绝
 *    ToolResponse，模型→工具路由（ReactAgent.makeModelToToolsEdge）见 last 为 ToolResponseMessage
 *    且 executedToolIds ⊇ requestedToolIds 直接路由回模型，工具节点根本不进；即便进了，
 *    AgentToolNode.handlePartialToolResponses 也会按 id 过滤掉已有响应的调用（双保险）。
 *    拒绝文案 "has been rejected by human" 以 ToolResponse 形式进入第二轮模型调用。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SaaInterruptionSpikeTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;
    private final List<String> toolExecutions = new CopyOnWriteArrayList<>();

    /** 被 approvalOn 命中的测试工具 */
    class EchoTool {
        @Tool(name = "echo_tool", description = "回显文本（spike 用）")
        public String echo(String text) { toolExecutions.add(text); return "echo:" + text; }
    }

    /**
     * 受控模型：第 1 次调用强制发起 echo_tool 调用，之后返回纯文本。
     * 按调用记录收到的消息文本（rejectPath 断言拒绝文案抵达模型、resume 输入污染断言拼接序）。
     */
    static class ScriptedModel implements ChatModel {
        int calls = 0;
        final List<List<String>> receivedPerCall = new CopyOnWriteArrayList<>();

        private AssistantMessage respond(Prompt prompt) {
            calls++;
            List<String> texts = new ArrayList<>();
            prompt.getInstructions().forEach(m -> {
                if (m.getText() != null && !m.getText().isEmpty()) {
                    texts.add(m.getText());
                }
                if (m instanceof ToolResponseMessage trm) {
                    trm.getResponses().forEach(r -> texts.add(r.responseData()));
                }
            });
            receivedPerCall.add(texts);
            return (calls == 1)
                ? AssistantMessage.builder().content("").properties(Map.of())
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "echo_tool", "{\"text\":\"hi\"}")))
                    .build()
                : AssistantMessage.builder().content("完成").properties(Map.of()).build();
        }

        @Override public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(respond(prompt))));
        }
        @Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    @BeforeAll
    void init() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(pg.getJdbcUrl()); ds.setUser(pg.getUsername()); ds.setPassword(pg.getPassword());
        dataSource = ds;
    }

    /** PER_CLASS 生命周期下单实例共享 toolExecutions，逐测清零隔离 */
    @BeforeEach
    void resetToolExecutions() {
        toolExecutions.clear();
    }

    private ReactAgent agent(ChatModel model, BaseCheckpointSaver saver) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(new EchoTool()).build().getToolCallbacks();
        HumanInTheLoopHook hook = HumanInTheLoopHook.builder()
            .approvalOn("echo_tool", "spike 审批").build();
        return ReactAgent.builder()
            .name("spike-agent").model(model).systemPrompt("你是测试助手")
            .tools(callbacks).hooks(List.of(hook)).saver(saver).build();
    }

    private InterruptionMetadata feedback(ToolFeedback.FeedbackResult result, String description) {
        return InterruptionMetadata.builder("spike", null)
            .addToolFeedback(ToolFeedback.builder()
                .id("call-1").name("echo_tool").arguments("{\"text\":\"hi\"}")
                .result(result).description(description).build())
            .build();
    }

    @Test
    void interruptAndResumeApprovePath() throws Exception {
        BaseCheckpointSaver saver = PostgresSaver.builder().datasource(dataSource).build();
        ScriptedModel model = new ScriptedModel();
        ReactAgent agent = agent(model, saver);

        String threadId = "spike-approve-" + System.nanoTime();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        // 第一段：跑至中断。观察点 A：outputs 的实际内容
        List<NodeOutput> outputs = new CopyOnWriteArrayList<>();
        agent.stream(new UserMessage("跑工具"), config).doOnNext(outputs::add).blockLast();

        outputs.forEach(o -> System.out.println("[approve/leg1] " + o.getClass().getSimpleName()
            + " node=" + o.node() + (o instanceof InterruptionMetadata im ? " feedbacks=" + im.toolFeedbacks() : "")));

        // A：最后一个元素是 InterruptionMetadata（extends NodeOutput），携带待审批 feedback
        NodeOutput last = outputs.get(outputs.size() - 1);
        assertThat(last).isInstanceOf(InterruptionMetadata.class);
        InterruptionMetadata interruption = (InterruptionMetadata) last;
        assertThat(interruption.node()).isEqualTo("_AGENT_HOOK_HITL");
        assertThat(interruption.toolFeedbacks()).hasSize(1);
        assertThat(interruption.toolFeedbacks().get(0).getId()).isEqualTo("call-1");
        assertThat(interruption.toolFeedbacks().get(0).getName()).isEqualTo("echo_tool");
        assertThat(interruption.toolFeedbacks().get(0).getResult()).isNull(); // 待审批：result 未定
        assertThat(interruption.toolFeedbacks().get(0).getArguments()).isEqualTo("{\"text\":\"hi\"}");
        // InterruptionMetadata 自带中断点状态快照：messages 里可取到待审批的 toolCall（Task 4 可直接用）
        Object messagesAtInterrupt = interruption.state().value("messages").orElseThrow();
        assertThat(messagesAtInterrupt).asInstanceOf(LIST)
            .anySatisfy(m -> assertThat(m.toString()).contains("echo_tool"));
        // 中断发生在工具执行之前
        assertThat(toolExecutions).isEmpty();
        // 倒数第二个元素是模型节点输出（工具调用请求先于中断流出）
        assertThat(outputs.get(outputs.size() - 2).node()).isEqualTo("_AGENT_MODEL_");

        // 第二段：带 APPROVED feedback resume。观察点 B：stream 第一参传 Map.of()（空 inputs）
        RunnableConfig resumeConfig = RunnableConfig.builder().threadId(threadId)
            .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                feedback(ToolFeedback.FeedbackResult.APPROVED, null))
            .build();
        List<NodeOutput> resumeOutputs = new CopyOnWriteArrayList<>();
        agent.stream(Map.of(), resumeConfig).doOnNext(resumeOutputs::add).blockLast();

        resumeOutputs.forEach(o -> System.out.println("[approve/leg2] " + o.getClass().getSimpleName() + " node=" + o.node()));

        assertThat(toolExecutions).containsExactly("hi"); // 批准 → 真实执行
        assertThat(model.calls).isEqualTo(2); // 第二轮模型收到工具结果后收口
        assertThat(resumeOutputs.get(resumeOutputs.size() - 1).isEND()).isTrue();
    }

    @Test
    void rejectPathDoesNotExecuteToolAndFeedsRejectionToModel() throws Exception {
        BaseCheckpointSaver saver = PostgresSaver.builder().datasource(dataSource).build();
        ScriptedModel model = new ScriptedModel();
        ReactAgent agent = agent(model, saver);

        String threadId = "spike-reject-" + System.nanoTime();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        List<NodeOutput> outputs = new CopyOnWriteArrayList<>();
        agent.stream(new UserMessage("跑工具"), config).doOnNext(outputs::add).blockLast();

        assertThat(outputs.get(outputs.size() - 1)).isInstanceOf(InterruptionMetadata.class);
        assertThat(toolExecutions).isEmpty();

        // REJECTED + 拒绝理由
        RunnableConfig resumeConfig = RunnableConfig.builder().threadId(threadId)
            .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                feedback(ToolFeedback.FeedbackResult.REJECTED, "测试拒绝理由"))
            .build();
        List<NodeOutput> resumeOutputs = new CopyOnWriteArrayList<>();
        agent.stream(Map.of(), resumeConfig).doOnNext(resumeOutputs::add).blockLast();

        resumeOutputs.forEach(o -> System.out.println("[reject/leg2] " + o.getClass().getSimpleName() + " node=" + o.node()));

        // C（按实测固化）：拒绝 → 未执行。工具节点根本没进（resume 流里无 _AGENT_TOOL_ 节点输出）
        assertThat(toolExecutions).isEmpty();
        assertThat(resumeOutputs).noneMatch(o -> o.node().equals("_AGENT_TOOL_"));
        // 拒绝文案以 ToolResponse 抵达第二轮模型调用（hook 内置文案）
        assertThat(model.receivedPerCall.get(1))
            .anySatisfy(t -> assertThat(t).contains("rejected by human"));
        assertThat(model.calls).isEqualTo(2);
        assertThat(resumeOutputs.get(resumeOutputs.size() - 1).isEND()).isTrue();
    }

    /**
     * B 的反面印证：resume 第一参传 UserMessage 也能跑通（feedback 消费/工具执行不受影响），
     * 但该消息经 GraphRunnerContext.initializeFromResume 的 state 合并被拼进消息历史，
     * 且插在 system 之后、原始用户消息之前（乱序）——生产路径 resume 必须传 Map.of()
     * （空 inputs），避免幻影用户消息污染会话记忆。
     */
    @Test
    void resumeWithUserMessagePollutesHistory() throws Exception {
        BaseCheckpointSaver saver = PostgresSaver.builder().datasource(dataSource).build();
        ScriptedModel model = new ScriptedModel();
        ReactAgent agent = agent(model, saver);

        String threadId = "spike-resume-input-" + System.nanoTime();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        agent.stream(new UserMessage("跑工具"), config).blockLast();

        RunnableConfig resumeConfig = RunnableConfig.builder().threadId(threadId)
            .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY,
                feedback(ToolFeedback.FeedbackResult.APPROVED, null))
            .build();
        agent.stream(new UserMessage("RESUME_MARKER"), resumeConfig).blockLast();

        System.out.println("[resume-input] model.receivedPerCall=" + model.receivedPerCall);
        assertThat(toolExecutions).containsExactly("hi"); // 机制仍跑通
        // 第二轮模型调用收到幻影消息。先断言 marker 确实进入了历史（否则下方 indexOf 比较会
        // 因 -1 < n 空真通过——无污染世界必须在此失败），再钉死实测乱序插入位：
        // 录制器能收到 system prompt（AgentLlmNode 排最前），故 [system, RESUME_MARKER, 跑工具, ...] 全序可钉
        List<String> secondCall = model.receivedPerCall.get(1);
        assertThat(secondCall).contains("RESUME_MARKER", "跑工具");
        assertThat(secondCall.indexOf("RESUME_MARKER"))
            .isGreaterThan(secondCall.indexOf("你是测试助手"))
            .isLessThan(secondCall.indexOf("跑工具"));
        assertThat(secondCall).anySatisfy(t -> assertThat(t).contains("echo:hi")); // responseData 为 JSON 字符串（带引号）
    }

}
