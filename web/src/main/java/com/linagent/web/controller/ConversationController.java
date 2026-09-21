package com.linagent.web.controller;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.persistence.CheckpointCleaner;
import com.linagent.agent.persistence.Conversation;
import com.linagent.agent.persistence.ConversationRepository;
import com.linagent.agent.persistence.MessageRepository;
import com.linagent.agent.persistence.TurnRepository;
import com.linagent.web.dto.ConversationResponse;
import com.linagent.web.dto.CreateConversationRequest;
import com.linagent.web.dto.TurnResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final MessageRepository messages;
    private final CheckpointCleaner checkpointCleaner;

    public ConversationController(ConversationRepository conversations,
                                  TurnRepository turns, MessageRepository messages,
                                  CheckpointCleaner checkpointCleaner) {
        this.conversations = conversations;
        this.turns = turns;
        this.messages = messages;
        this.checkpointCleaner = checkpointCleaner;
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
        Conversation saved = conversations.save(new Conversation(first.id(), first.title(),
            "conv-" + first.id(), first.compactSummary(), first.compactedTurnSeq(),
            first.tenantId(), first.userId(), first.createdAt(), first.updatedAt()));
        return new ConversationResponse(saved.id(), saved.title(), 0, saved.updatedAt().toString());
    }

    @GetMapping
    public List<ConversationResponse> list() {
        AuthContext ctx = AuthContextHolder.require();
        return conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc(ctx.tenantId(), ctx.userId()).stream()
            .map(c -> new ConversationResponse(c.id(), c.title(),
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
        // CheckpointCleaner 按 threadId 模式清理。先清 checkpoint 再删会话行：
        // 中途失败时重试安全（会话仍在，幂等清理）。
        conversations.requireOwned(id, ctx.tenantId(), ctx.userId());
        checkpointCleaner.deleteByConversationId(id);
        conversations.deleteById(id);
    }
}
