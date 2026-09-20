package com.javaagent.web.controller;

import com.javaagent.agent.persistence.CheckpointCleaner;
import com.javaagent.agent.persistence.Conversation;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.TurnRepository;
import com.javaagent.web.dto.ConversationResponse;
import com.javaagent.web.dto.CreateConversationRequest;
import com.javaagent.web.dto.TurnResponse;
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
        String title = (request == null || request.title() == null || request.title().isBlank())
            ? "新会话" : request.title();
        Conversation saved = conversations.save(new Conversation(null, title,
            "conv-" + System.nanoTime(), null, Instant.now(), Instant.now()));
        return new ConversationResponse(saved.id(), saved.title(), 0, saved.updatedAt().toString());
    }

    @GetMapping
    public List<ConversationResponse> list() {
        return conversations.findAllByOrderByUpdatedAtDesc().stream()
            .map(c -> new ConversationResponse(c.id(), c.title(),
                turns.countByConversationId(c.id()), c.updatedAt().toString()))
            .toList();
    }

    @GetMapping("/{id}/turns")
    public List<TurnResponse> turns(@PathVariable Long id) {
        if (conversations.findById(id).isEmpty()) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        return turns.findByConversationIdOrderBySeqAsc(id).stream()
            .map(t -> TurnResponse.from(t, messages.findByTurnIdOrderBySeq(t.id())))
            .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        // 级联删除：turn/message 由 PG 外键 ON DELETE CASCADE 承担；
        // checkpoint（graphthread/graphcheckpoint）由 CheckpointCleaner 按 threadId 模式清理。
        // 先清 checkpoint 再删会话行：中途失败时重试安全（会话仍在，幂等清理）。
        conversations.findById(id).ifPresent(c -> {
            checkpointCleaner.deleteByConversationId(id);
            conversations.deleteById(id);
        });
    }
}
