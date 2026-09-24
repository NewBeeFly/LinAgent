package com.linagent.web.controller;

import com.linagent.agent.approval.InMemorySessionRules;
import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.conversation.ChatMode;
import com.linagent.agent.facade.ApprovalPendingException;
import com.linagent.agent.persistence.support.CheckpointCleaner;
import com.linagent.agent.persistence.po.Conversation;
import com.linagent.agent.persistence.repository.ConversationAccessDeniedException;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.repository.TurnRepository;
import com.linagent.web.dto.ConversationResponse;
import com.linagent.web.dto.CreateConversationRequest;
import com.linagent.web.dto.ModeResponse;
import com.linagent.web.dto.TurnResponse;
import com.linagent.web.dto.UpdateModeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final CheckpointCleaner checkpointCleaner;
    private final InMemorySessionRules sessionRules;

    public ConversationController(ConversationRepository conversations,
                                  TurnRepository turns, MessageRepository messages,
                                  CheckpointCleaner checkpointCleaner,
                                  InMemorySessionRules sessionRules) {
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.checkpointCleaner = checkpointCleaner;
        this.sessionRules = sessionRules;
    }

    @PostMapping
    public ConversationResponse create(@RequestBody(required = false) CreateConversationRequest request) {
        AuthContext ctx = AuthContextHolder.require();
        String title = (request == null || request.title() == null || request.title().isBlank())
            ? "新会话" : request.title();
        // 两段式落库（终审 Important 1）：先 save 拿 id，再落 threadId = "conv-{id}"
        // （spec §3 threadId=conversationId）。id 由 PG 序列生成，落库前无从预知；
        // threadId 必须与 CheckpointCleaner 的删除模式（conv-{id} / conv-{id}-v%）对齐，
        // 否则未压缩会话（threadId 从未换代）的 checkpoint 在会话删除后成为永久孤儿。
        // 临时值只需绕开 NOT NULL 且不与正式模式冲突，落库即被第二段覆盖。
        Conversation first = conversations.save(
            Conversation.create(title, "pending-" + System.nanoTime(), ctx.tenantId(), ctx.userId(), Instant.now()));
        // 摘要与压缩锚点已由 hook 体系（SummarizingModelHook）接管，compact_summary/compacted_turn_seq
        // 列停用（compacted_turn_seq 为 NOT NULL DEFAULT 0，按无锚点语义显式落 0）
        Conversation saved = conversations.save(new Conversation(first.id(), first.title(),
            "conv-" + first.id(), null, 0,
            first.tenantId(), first.userId(), first.mode(), first.createdAt(), first.updatedAt()));
        return new ConversationResponse(saved.id(), saved.title(), saved.mode(), 0, saved.updatedAt().toString());
    }

    @GetMapping
    public List<ConversationResponse> list() {
        AuthContext ctx = AuthContextHolder.require();
        return conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc(ctx.tenantId(), ctx.userId()).stream()
            .map(c -> new ConversationResponse(c.id(), c.title(), c.mode(),
                turns.countByConversationId(c.id()), c.updatedAt().toString()))
            .toList();
    }

    @GetMapping("/{id}/turns")
    public List<TurnResponse> turns(@PathVariable Long id) {
        AuthContext ctx = AuthContextHolder.require();
        conversations.requireOwned(id, ctx.tenantId(), ctx.userId());
        return turns.findByConversationIdOrderBySeqAsc(id).stream()
            .map(t -> TurnResponse.from(t, messages.findByTurnIdOrderBySeq(t.id())))
            .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        AuthContext ctx = AuthContextHolder.require();
        // 非属主/不存在统一 404（不泄漏存在性）；级联删除：turn/message 由 PG 外键
        // ON DELETE CASCADE 承担；checkpoint（graphthread/graphcheckpoint）由
        // CheckpointCleaner 按 threadId 模式清理；本会话的 session 审批规则由
        // sessionRules.evict 清出内存（终审 M1，与 InMemorySessionRules javadoc 对齐）。
        // 先清 checkpoint/session 规则再删会话行：中途失败时重试安全（幂等清理）。
        conversations.requireOwned(id, ctx.tenantId(), ctx.userId());
        checkpointCleaner.deleteByConversationId(id);
        sessionRules.evict(id);
        conversations.deleteById(id);
    }

    /**
     * 切换会话模式（modes Task 3，spec V8）：三档严格校验——本端点不静默回落 STANDARD
     * （切档是显式意图，打错字静默变档比 400 更危险）；归属 404 统一语义；存在
     * WAITING_APPROVAL 轮时 409 挡回（复用审批 pending 语义：先决议再切档，避免
     * 审批期间的会话跨档续跑）。落库仅改 mode 与 updatedAt，其余字段原样转发。
     */
    @PutMapping("/{id}/mode")
    public ModeResponse updateMode(@PathVariable Long id,
                                   @RequestBody(required = false) UpdateModeRequest request) {
        AuthContext ctx = AuthContextHolder.require();
        ChatMode mode = requireValidMode(request == null ? null : request.mode());
        conversations.requireOwned(id, ctx.tenantId(), ctx.userId());
        if (turns.existsByConversationIdAndStatus(id, "WAITING_APPROVAL")) {
            throw new ApprovalPendingException(id);
        }
        Conversation conv = conversations.findById(id)
            .orElseThrow(() -> new ConversationAccessDeniedException(id));
        conversations.save(new Conversation(conv.id(), conv.title(), conv.threadId(),
            conv.compactSummary(), conv.compactedTurnSeq(), conv.tenantId(), conv.userId(),
            mode.name(), conv.createdAt(), Instant.now()));
        return new ModeResponse(id, mode.name());
    }

    /** 严格解析：trim + 大小写归一后按枚举名匹配；null/空白/未知值一律 400（不走 parse 回落） */
    private static ChatMode requireValidMode(String raw) {
        if (raw != null) {
            try {
                return ChatMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                // 落到统一 400 分支
            }
        }
        throw new InvalidChatModeException(raw);
    }
}
