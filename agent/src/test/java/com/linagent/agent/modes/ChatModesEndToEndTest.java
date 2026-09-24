package com.linagent.agent.modes;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.conversation.ChatMode;
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
 * 会话三档 E2E（v0.3 spec §11 第三条落点）：真实全链路——AgentFacade → AgentFactory
 * 三档构造分支 → Testcontainers PG（会话/轮次/消息/checkpoint 全真表），唯一替身为
 * 脚本 ChatModel（@Primary）。STANDARD 档（审批中断/批准/拒绝）由
 * {@code ApprovalEndToEndTest} 守护，此处覆盖另外两档的成对对照：
 *
 * <ul>
 *   <li>{@link ChatMode#AUTO}——免审批：同一 "RUN:mkdir" 输入（STANDARD 档会中断等待审批）
 *       直接触发工具执行（无 ApprovalRequest、目录真实落地、工具结果回传模型后收口）；</li>
 *   <li>{@link ChatMode#CHAT}——完全无工具：脚本模型刻意发起 toolCall（同一输入在 AUTO 档
 *       会真实建目录）——SAA 空工具图无工具节点（model → END 直连），调用不执行、无任何
 *       工具/审批事件、图单轮直接收口；且模型收到的 system prompt 含纯聊声明
 *       （spec §2.3：堵住"口头承诺做事"的幻觉）。</li>
 * </ul>
 *
 * <p>容器/桩模式复用 ApprovalEndToEndTest（E2eBoot 组合根 + ScriptedModel 按调用录制
 * 全部消息文本——CHAT 档据此断言 prompt 声明送达模型）。
 */
@SpringBootTest(classes = ChatModesEndToEndTest.E2eBoot.class, properties = {
    // 测试 schema 全量初始化（跳过 V4 app_user：agent 模块无鉴权链，AuthContext 直设）
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__init.sql,"
        + "classpath:db/migration/V2__checkpoint.sql,"
        + "classpath:db/migration/V3__compaction_anchor.sql,"
        + "classpath:db/migration/V5__conversation_ownership.sql,"
        + "classpath:db/migration/V6__permission_rule.sql,"
        + "classpath:db/migration/V7__turn_waiting_approval.sql,"
        + "classpath:db/migration/V8__chat_mode.sql",
    // OpenAI 自动装配占位 key（真实调用永不发生——scripted 模型 @Primary 全量接管）
    "spring.ai.openai.api-key=e2e-placeholder"
})
@Testcontainers
class ChatModesEndToEndTest {

    /**
     * 测试专用组合根（镜像 WebApplication 的装配姿势，同 ApprovalEndToEndTest）：
     * 全链路 bean 显式扫描 + persistence 仓储指路，scripted ChatModel @Primary 接管
     * 全部注入点（AgentFactory/SummarizingModelHook）。
     *
     * <p>扫描排除测试类：classpath 上 test-classes 与 main 同包树，不排除会扫进
     * 其他测试的嵌套组合根（ApprovalEndToEndTest$E2eBoot 的同名 @Primary
     * scriptedChatModel Bean 冲突）；本类经 classes= 显式注册，不受自身过滤影响。
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.ComponentScan(basePackages = "com.linagent.agent",
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX, pattern = ".*Test(\\$.*)?"))
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

    /** 独立工作区/技能目录（静态初始化先于 @DynamicPropertySource；与审批 E2E 互不串扰） */
    static final Path workspaceRoot = createDir("e2e-modes-workspace");
    static final Path skillsRoot = createDir("e2e-modes-skills");

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

    /** 两段式落库（threadId=conv-{id} 硬契约），mode 直写会话行（v0.3 档位种子） */
    private Conversation newConversation(String title, ChatMode mode) {
        Conversation first = conversations.save(
            Conversation.create(title, "pending-" + System.nanoTime(), CTX.tenantId(), CTX.userId(), Instant.now()));
        return conversations.save(new Conversation(first.id(), first.title(),
            "conv-" + first.id(), null, 0, first.tenantId(), first.userId(),
            mode.name(), first.createdAt(), first.updatedAt()));
    }

    @Test
    void autoModeExecutesShellDirectlyWithoutApprovalGate() {
        Conversation conv = newConversation("E2E AUTO 直执行", ChatMode.AUTO);
        Path personalRoot = workspaceResolver.personalRoot(CTX.tenantId(), CTX.userId());
        Path createdDir = personalRoot.resolve("auto_demo");
        assertThat(createdDir).doesNotExist();

        // 同一 "RUN:mkdir" 输入：STANDARD 档中断等审批（ApprovalEndToEndTest），
        // AUTO 档 ApprovalHook 整体缺席——工具直执行、流自然收口
        List<AgentEvent> events = facade.chat(conv.id(), "RUN:mkdir auto_demo")
            .collectList().block(Duration.ofSeconds(120));

        // 免审批：全程无 ApprovalRequest，也不停在等待态
        assertThat(events).noneMatch(AgentEvent.ApprovalRequest.class::isInstance);

        // 工具真实执行（拦截器发 ToolCall/ToolResult 事件，成功回传，目录落地）
        AgentEvent.ToolResult toolResult = events.stream()
            .filter(AgentEvent.ToolResult.class::isInstance)
            .map(AgentEvent.ToolResult.class::cast)
            .findFirst().orElseThrow(() -> new AssertionError("AUTO 档未见工具执行，事件: " + events));
        assertThat(toolResult.callId()).isEqualTo("call-1");
        assertThat(toolResult.success()).isTrue();
        assertThat(createdDir).isDirectory();

        // 工具结果回传模型（第二轮模型输入含 shell 输出）→ 脚本模型收口文本 → TurnDone
        assertThat(scriptedModel.receivedPerCall()).hasSize(2);
        assertThat(events.stream().filter(AgentEvent.MessageDelta.class::isInstance)).isNotEmpty();
        List<AgentEvent.TurnDone> dones = events.stream()
            .filter(AgentEvent.TurnDone.class::isInstance)
            .map(AgentEvent.TurnDone.class::cast)
            .toList();
        assertThat(dones).hasSize(1);
        assertThat(dones.get(0).finishReason()).isEqualTo("STOP");
        assertThat(turns.findById(dones.get(0).turnId()).orElseThrow().status()).isEqualTo("COMPLETED");

        // AUTO 档 prompt 不含纯聊声明（档位声明只在 CHAT 档追加）
        assertThat(scriptedModel.receivedPerCall().get(0))
            .noneSatisfy(text -> assertThat(text).contains("系统未向你提供任何工具"));

        // 展示层完整留痕：USER + 执行轨迹 TOOL_CALL/TOOL_RESULT（同 callId 各一行）+ 收口 TEXT
        List<Message> rows = messages.findByTurnIdOrderBySeq(dones.get(0).turnId());
        assertThat(rows).extracting(Message::msgType)
            .containsExactly("USER", "TOOL_CALL", "TOOL_RESULT", "TEXT");
        List<Message> callRows = rows.stream().filter(m -> "TOOL_CALL".equals(m.msgType())).toList();
        assertThat(callRows).hasSize(1);
        assertThat(callRows.get(0).callId()).isEqualTo("call-1");
        assertThat(callRows.get(0).toolName()).isEqualTo("shell");
    }

    @Test
    void chatModeHasNoToolsAndDeclaresPureChatInPrompt() {
        Conversation conv = newConversation("E2E CHAT 纯聊", ChatMode.CHAT);
        Path personalRoot = workspaceResolver.personalRoot(CTX.tenantId(), CTX.userId());
        Path wouldBeDir = personalRoot.resolve("chat_demo");
        assertThat(wouldBeDir).doesNotExist();

        // 脚本模型刻意发 toolCall（同一输入在 AUTO 档会真实建目录）——CHAT 档 tools 为空，
        // SAA 空工具图 model → END 直连：调用无处执行，也不产生任何工具/审批事件
        List<AgentEvent> events = facade.chat(conv.id(), "RUN:mkdir chat_demo")
            .collectList().block(Duration.ofSeconds(120));

        // 模型收到的 system prompt 含纯聊声明（spec §2.3，防口头承诺做事）
        assertThat(scriptedModel.receivedPerCall().get(0))
            .anySatisfy(text -> assertThat(text).contains("忽略对话历史中出现过的任何工具调用示例"));

        // 无工具可调：调用未执行、无工具/审批事件、目录未出现
        assertThat(events).noneMatch(AgentEvent.ToolCall.class::isInstance);
        assertThat(events).noneMatch(AgentEvent.ToolResult.class::isInstance);
        assertThat(events).noneMatch(AgentEvent.ApprovalRequest.class::isInstance);
        assertThat(wouldBeDir).doesNotExist();

        // 空工具图无循环：模型只被调一轮（无工具结果可回传），图直接收口 TurnDone
        assertThat(scriptedModel.receivedPerCall()).hasSize(1);
        List<AgentEvent.TurnDone> dones = events.stream()
            .filter(AgentEvent.TurnDone.class::isInstance)
            .map(AgentEvent.TurnDone.class::cast)
            .toList();
        assertThat(dones).hasSize(1);
        assertThat(dones.get(0).finishReason()).isEqualTo("STOP");
        assertThat(turns.findById(dones.get(0).turnId()).orElseThrow().status()).isEqualTo("COMPLETED");

        // 展示层仅 USER 一行：无处执行的 toolCall 不落执行轨迹、也无正文收口行
        List<Message> rows = messages.findByTurnIdOrderBySeq(dones.get(0).turnId());
        assertThat(rows).extracting(Message::msgType).containsExactly("USER");
    }

    /**
     * 脚本模型（ApprovalEndToEndTest 同款姿势）：最后一条消息为 "RUN:"-前缀 UserMessage 时
     * 发起 shell 工具调用（命令取前缀后文本），否则收口纯文本——按调用记录全部消息文本
     * （CHAT 档断言 system prompt 声明送达模型）。无跨会话状态，@BeforeEach 仅清录制。
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
