package com.linagent.agent.approval;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.facade.AgentEvent;
import com.linagent.agent.facade.AgentFacade;
import com.linagent.agent.persistence.po.Conversation;
import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.po.Turn;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.repository.TurnRepository;
import com.linagent.agent.workspace.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端（spec §11 第二条落点）：真实全链路——AgentFacade → AgentFactory（真 ReactAgent +
 * ShellTool2 + ApprovalHook + checkpoint）→ Testcontainers PG（会话/消息/规则/checkpoint 全真表），
 * 唯一替身为脚本 ChatModel（@Primary，无外部 LLM 依赖）。
 *
 * <p>批准路径：非白名单 shell（mkdir）触发中断（ApprovalRequest 流尾、无 TurnDone、
 * turn WAITING_APPROVAL、TOOL_CALL 行提前落库、工具未执行）→ resume(APPROVED) → 工具真实执行
 * （个人工作区目录出现）→ 续流正文 → TurnDone、turn COMPLETED。
 * 对照拒绝路径：resume(REJECTED+reason) → 工具不执行、拒绝文案（含理由）以 tool result 抵达
 * 第二轮模型输入、turn COMPLETED。
 *
 * <p>复用 SaaInterruptionSpikeTest 容器模式 + CheckpointRestartTest 的 PostgresSaver 直连姿势
 * （saver 由 AgentBeansConfig 以 CREATE_NONE 装配，表由 sql.init 的 V2 建）。
 */
@SpringBootTest(classes = ApprovalEndToEndTest.E2eBoot.class, properties = {
    // 测试 schema 全量初始化（跳过 V4 app_user：agent 模块无鉴权链，AuthContext 直设）
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__init.sql,"
        + "classpath:db/migration/V2__checkpoint.sql,"
        + "classpath:db/migration/V3__compaction_anchor.sql,"
        + "classpath:db/migration/V5__conversation_ownership.sql,"
        + "classpath:db/migration/V6__permission_rule.sql,"
        + "classpath:db/migration/V7__turn_waiting_approval.sql",
    // OpenAI 自动装配占位 key（真实调用永不发生——scripted 模型 @Primary 全量接管）
    "spring.ai.openai.api-key=e2e-placeholder"
})
@Testcontainers
class ApprovalEndToEndTest {

    /**
     * 测试专用组合根（镜像 WebApplication 的装配姿势）：TestApplication 无 @ComponentScan
     * （探针测试只吃自动装配），全链路 bean 须在此显式扫描；仓储接口在 persistence 包，
     * 自动配置默认只扫本配置类所在包——@EnableJdbcRepositories 显式指路。scripted
     * ChatModel 以 @Primary 接管全部注入点（AgentFactory/SummarizingModelHook）。
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.ComponentScan(basePackages = "com.linagent.agent")
    @org.springframework.data.jdbc.repository.config.EnableJdbcRepositories(
        basePackages = "com.linagent.agent.persistence")
    static class E2eBoot {
        @Bean
        @Primary
        ScriptedModel scriptedChatModel() {
            return new ScriptedModel();
        }
    }

    @Container
    @ServiceConnection
    // stringtype=unspecified：String 直写 JSONB（message.arguments/turn.usage）
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    /** 独立工作区/技能目录（静态初始化先于 @DynamicPropertySource） */
    static final Path workspaceRoot = createDir("e2e-approval-workspace");
    static final Path skillsRoot = createDir("e2e-approval-skills");

    static Path createDir(String prefix) {
        try {
            Path dir = Files.createTempDirectory(prefix);
            Files.writeString(dir.resolve("README.md"), "e2e 占位，避免空目录语义差异\n");
            return dir;
        }
        catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void agentProps(DynamicPropertyRegistry registry) {
        registry.add("agent.workspace-root", () -> workspaceRoot.toString());
        registry.add("agent.skills-root", () -> skillsRoot.toString());
    }

    @Autowired
    AgentFacade facade;
    @Autowired
    ScriptedModel scriptedModel;
    @Autowired
    ConversationRepository conversations;
    @Autowired
    TurnRepository turns;
    @Autowired
    MessageRepository messages;
    @Autowired
    WorkspaceResolver workspaceResolver;

    private static final AuthContext CTX = new AuthContext("default", "linmj");

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(CTX);
        scriptedModel.reset();
    }

    @AfterEach
    void cleanAuthContext() {
        AuthContextHolder.clear();
    }

    /** 两段式落库（threadId=conv-{id} 硬契约，与 ConversationController 同款） */
    private Conversation newConversation(String title) {
        Conversation first = conversations.save(
            Conversation.create(title, "pending-" + System.nanoTime(), CTX.tenantId(), CTX.userId(), Instant.now()));
        return conversations.save(new Conversation(first.id(), first.title(),
            "conv-" + first.id(), null, 0, first.tenantId(), first.userId(), first.createdAt(), first.updatedAt()));
    }

    @Test
    void endToEndChatInterruptResumeApproveCompletesTurn() {
        Conversation conv = newConversation("E2E 批准路径");
        Path personalRoot = workspaceResolver.personalRoot(CTX.tenantId(), CTX.userId());
        Path createdDir = personalRoot.resolve("demo");
        assertThat(createdDir).doesNotExist();

        // 第一段：跑至中断（mkdir 非白名单 → ApprovalHook 中断）
        List<AgentEvent> events = facade.chat(conv.id(), "RUN:mkdir demo")
            .collectList().block(Duration.ofSeconds(120));

        AgentEvent.ApprovalRequest approval = events.stream()
            .filter(AgentEvent.ApprovalRequest.class::isInstance)
            .map(AgentEvent.ApprovalRequest.class::cast)
            .findFirst().orElseThrow(() -> new AssertionError("未收到 ApprovalRequest，事件: " + events));
        // 无 TurnDone（中断以 ApprovalRequest 收尾）
        assertThat(events).noneMatch(AgentEvent.TurnDone.class::isInstance);

        var item = approval.items().get(0);
        assertThat(item.callId()).isEqualTo("call-1");
        assertThat(item.toolName()).isEqualTo("shell");
        assertThat(item.payload()).isEqualTo("mkdir demo");
        assertThat(item.suggestedRule()).isEqualTo("mkdir demo *");
        // 中断发生在工具执行之前
        assertThat(createdDir).doesNotExist();

        // turn 停 WAITING_APPROVAL；TOOL_CALL 行提前落库（审批卡片数据源）
        Turn waiting = turns.findById(approval.turnId()).orElseThrow();
        assertThat(waiting.status()).isEqualTo("WAITING_APPROVAL");
        List<Message> rows = messages.findByTurnIdOrderBySeq(approval.turnId());
        assertThat(rows).extracting(Message::msgType).containsExactly("USER", "TOOL_CALL");
        assertThat(rows.get(1).callId()).isEqualTo("call-1");
        assertThat(rows.get(1).toolName()).isEqualTo("shell");

        // 第二段：决议批准 → resume 续跑
        InterruptionMetadata feedback = InterruptionMetadata.builder(ApprovalHook.HITL_NODE_FULL_NAME, null)
            .addToolFeedback(ToolFeedback.builder()
                .id(item.callId()).name(item.toolName()).arguments(item.arguments())
                .result(ToolFeedback.FeedbackResult.APPROVED).build())
            .build();
        List<AgentEvent> resumeEvents = facade.resume(conv.id(), feedback)
            .collectList().block(Duration.ofSeconds(120));

        // 工具真实执行（interceptor 发 ToolCall/ToolResult 事件，成功回传）
        AgentEvent.ToolResult toolResult = resumeEvents.stream()
            .filter(AgentEvent.ToolResult.class::isInstance)
            .map(AgentEvent.ToolResult.class::cast)
            .findFirst().orElseThrow(() -> new AssertionError("批准后未见工具执行，事件: " + resumeEvents));
        assertThat(toolResult.callId()).isEqualTo("call-1");
        assertThat(toolResult.success()).isTrue();
        assertThat(createdDir).isDirectory();

        // 续流正文 → TurnDone(COMPLETED)，turn 终态 COMPLETED
        assertThat(resumeEvents.stream().filter(AgentEvent.MessageDelta.class::isInstance))
            .isNotEmpty();
        List<AgentEvent.TurnDone> dones = resumeEvents.stream()
            .filter(AgentEvent.TurnDone.class::isInstance)
            .map(AgentEvent.TurnDone.class::cast)
            .toList();
        assertThat(dones).hasSize(1);
        assertThat(dones.get(0).turnId()).isEqualTo(approval.turnId());
        assertThat(dones.get(0).finishReason()).isEqualTo("STOP");
        assertThat(turns.findById(approval.turnId()).orElseThrow().status()).isEqualTo("COMPLETED");

        // 展示层完整留痕：USER 1 条（resume 不新增用户行）+ 批准执行轨迹 TOOL_CALL×2/TOOL_RESULT + 收口 TEXT
        List<Message> finalRows = messages.findByTurnIdOrderBySeq(approval.turnId());
        assertThat(finalRows).extracting(Message::msgType)
            .containsExactly("USER", "TOOL_CALL", "TOOL_CALL", "TOOL_RESULT", "TEXT");
        assertThat(finalRows.stream().filter(m -> "USER".equals(m.msgType()))).hasSize(1);
    }

    @Test
    void endToEndResumeRejectSkipsToolAndFeedsRejectionToModel() {
        Conversation conv = newConversation("E2E 拒绝路径");
        Path personalRoot = workspaceResolver.personalRoot(CTX.tenantId(), CTX.userId());

        List<AgentEvent> events = facade.chat(conv.id(), "RUN:mkdir rejected_dir")
            .collectList().block(Duration.ofSeconds(120));
        AgentEvent.ApprovalRequest approval = events.stream()
            .filter(AgentEvent.ApprovalRequest.class::isInstance)
            .map(AgentEvent.ApprovalRequest.class::cast)
            .findFirst().orElseThrow();
        assertThat(personalRoot.resolve("rejected_dir")).doesNotExist();

        // 决议拒绝 + 理由 → resume
        InterruptionMetadata feedback = InterruptionMetadata.builder(ApprovalHook.HITL_NODE_FULL_NAME, null)
            .addToolFeedback(ToolFeedback.builder()
                .id(approval.items().get(0).callId()).name("shell")
                .arguments(approval.items().get(0).arguments())
                .result(ToolFeedback.FeedbackResult.REJECTED)
                .description("测试拒绝理由：不要创建目录")
                .build())
            .build();
        List<AgentEvent> resumeEvents = facade.resume(conv.id(), feedback)
            .collectList().block(Duration.ofSeconds(120));

        // 工具未执行（spike 结论 C：路由层短路 + 节点过滤双保险）
        assertThat(resumeEvents).noneMatch(AgentEvent.ToolResult.class::isInstance);
        assertThat(personalRoot.resolve("rejected_dir")).doesNotExist();

        // 拒绝文案（含理由）以 tool result 抵达第二轮模型输入
        assertThat(scriptedModel.receivedPerCall().get(scriptedModel.receivedPerCall().size() - 1))
            .anySatisfy(text -> assertThat(text)
                .contains("rejected by human")
                .contains("测试拒绝理由：不要创建目录"));

        // 轮次自然收口：TurnDone + COMPLETED（spec §6：拒绝不终止轮，图自然走完）
        assertThat(resumeEvents.stream().filter(AgentEvent.TurnDone.class::isInstance)).hasSize(1);
        assertThat(turns.findById(approval.turnId()).orElseThrow().status()).isEqualTo("COMPLETED");

        List<Message> finalRows = messages.findByTurnIdOrderBySeq(approval.turnId());
        assertThat(finalRows).extracting(Message::msgType)
            .containsExactly("USER", "TOOL_CALL", "TEXT");
    }

    /**
     * 脚本模型（SaaInterruptionSpikeTest 同款姿势）：最后一条消息为 "RUN:"-前缀 UserMessage 时
     * 发起 shell 工具调用（命令取前缀后文本），否则收口纯文本——按调用记录全部消息文本
     * （拒绝路径断言拒绝文案抵达模型）。无跨会话状态，@BeforeEach 仅清录制。
     */
    static class ScriptedModel implements ChatModel {

        private final List<List<String>> receivedPerCall = new CopyOnWriteArrayList<>();

        void reset() {
            receivedPerCall.clear();
        }

        List<List<String>> receivedPerCall() {
            return receivedPerCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<org.springframework.ai.chat.messages.Message> instructions = prompt.getInstructions();
            org.springframework.ai.chat.messages.Message last = instructions.get(instructions.size() - 1);
            List<String> texts = new ArrayList<>();
            for (org.springframework.ai.chat.messages.Message m : instructions) {
                if (m.getText() != null && !m.getText().isEmpty()) {
                    texts.add(m.getText());
                }
                if (m instanceof ToolResponseMessage toolResponses) {
                    toolResponses.getResponses().forEach(r -> texts.add(r.responseData()));
                }
            }
            receivedPerCall.add(texts);

            AssistantMessage assistant = (last instanceof UserMessage userMessage
                    && userMessage.getText() != null && userMessage.getText().startsWith("RUN:"))
                ? AssistantMessage.builder().content("").properties(Map.of())
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "shell",
                        "{\"command\":\"" + userMessage.getText().substring("RUN:".length()) + "\"}")))
                    .build()
                : AssistantMessage.builder().content("完成").properties(Map.of()).build();
            return new ChatResponse(List.of(new Generation(assistant)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }
}
