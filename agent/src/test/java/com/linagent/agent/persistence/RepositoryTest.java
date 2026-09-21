package com.linagent.agent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJdbcTest(properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__init.sql,"
        + "classpath:db/migration/V3__compaction_anchor.sql,classpath:db/migration/V5__conversation_ownership.sql"
})
@Import(JdbcConverterConfig.class) // JSONB 列读取转换（PGobject -> String）
@Testcontainers
class RepositoryTest {

    @Container
    @ServiceConnection
    // stringtype=unspecified：允许以 String 直写 JSONB 列（turn.usage / message.arguments）
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withUrlParam("stringtype", "unspecified");

    @Autowired
    ConversationRepository conversations;
    @Autowired
    TurnRepository turns;
    @Autowired
    MessageRepository messages;

    @Test
    void saveAndQueryConversationTurnMessage() {
        Conversation conv = conversations.save(
            new Conversation(null, "测试会话", "conv-1", null, 0, "default", "linmj", Instant.now(), Instant.now()));

        Turn turn = turns.save(new Turn(null, conv.id(), 1, "RUNNING",
            null, null, Instant.now(), null));

        messages.save(new Message(null, turn.id(), 0, "USER", "你好", null, null, null, null, null, null, Instant.now()));
        messages.save(new Message(null, turn.id(), 1, "THINKING", "思考内容", null, null, null, null, null, null, Instant.now()));
        messages.save(new Message(null, turn.id(), 2, "TOOL_CALL", null, "call-1", "list_dir",
            "{\"path\":\".\"}", null, null, null, Instant.now()));
        messages.save(new Message(null, turn.id(), 3, "TOOL_RESULT", null, "call-1", "list_dir",
            null, "a.txt\nb/", true, 120L, Instant.now()));

        assertThat(turns.countByConversationId(conv.id())).isEqualTo(1);
        List<Message> turnMessages = messages.findByTurnIdOrderBySeq(turn.id());
        assertThat(turnMessages).hasSize(4).extracting(Message::msgType)
            .containsExactly("USER", "THINKING", "TOOL_CALL", "TOOL_RESULT");

        List<Message> timeline = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(timeline).hasSize(4);
    }

    @Test
    void compactSummaryRoundTrip() {
        Conversation conv = conversations.save(
            new Conversation(null, "压缩会话", "conv-2", "此前会话摘要内容", 3, "default", "linmj", Instant.now(), Instant.now()));
        Conversation reloaded = conversations.findById(conv.id()).orElseThrow();
        assertThat(reloaded.compactSummary()).isEqualTo("此前会话摘要内容");
        // V3 压缩锚点：记录已摘要到哪轮，与 compact_summary 同为记忆量纲的持久化字段
        assertThat(reloaded.compactedTurnSeq()).isEqualTo(3);
    }

    @Test
    void conversationTouchUpdatesUpdatedAt() {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        Conversation conv = conversations.save(
            new Conversation(null, "旧会话", "conv-3", null, 0, "default", "linmj", past, past));

        conversations.touch(conv.id());

        Conversation reloaded = conversations.findById(conv.id()).orElseThrow();
        assertThat(reloaded.updatedAt()).isAfter(past);
    }

    @Test
    void findByTenantAndUserOrdersByUpdatedAtDesc() {
        Instant base = Instant.parse("2020-01-01T00:00:00Z");
        conversations.save(new Conversation(null, "旧", "c-1", null, 0, "default", "linmj", base, base));
        conversations.save(new Conversation(null, "新", "c-2", null, 0, "default", "linmj", base, base.plusSeconds(3600)));

        List<Conversation> ordered = conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc("default", "linmj");
        assertThat(ordered).extracting(Conversation::threadId).containsExactly("c-2", "c-1");
    }

    /** 多租户 v0.2：列表/单查均按 (tenantId, userId) 收敛，非属主不可见 */
    @Test
    void conversationOwnershipScopesQueries() {
        Conversation a = conversations.save(Conversation.create("A", "conv-x", "default", "linmj", Instant.now()));
        conversations.save(Conversation.create("B", "conv-y", "default", "tester", Instant.now()));

        assertThat(conversations.findByTenantIdAndUserIdOrderByUpdatedAtDesc("default", "linmj"))
            .extracting(Conversation::id).containsExactly(a.id());
        assertThat(conversations.findByIdAndTenantIdAndUserId(a.id(), "default", "tester")).isEmpty();
        assertThat(conversations.findByIdAndTenantIdAndUserId(a.id(), "default", "linmj")).hasValue(a);
    }

    @Test
    void turnLifecycleAndQueries() {
        Conversation conv = conversations.save(
            new Conversation(null, "多轮会话", "conv-4", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        Turn first = turns.save(Turn.running(conv.id(), 1));
        turns.save(first.complete("stop", "{\"total_tokens\":42}"));
        turns.save(Turn.running(conv.id(), 2));

        assertThat(turns.countByConversationId(conv.id())).isEqualTo(2);
        assertThat(turns.findTopByConversationIdOrderBySeqDesc(conv.id()))
            .hasValueSatisfying(last -> {
                assertThat(last.seq()).isEqualTo(2);
                assertThat(last.status()).isEqualTo("RUNNING");
            });

        List<Turn> ordered = turns.findByConversationIdOrderBySeqAsc(conv.id());
        assertThat(ordered).extracting(Turn::seq).containsExactly(1, 2);
        // JSONB usage 列读回 String 的值级闭环（经 JdbcConverterConfig 的 PGobject -> String 转换）
        Turn completed = ordered.get(0);
        assertThat(completed.usage()).contains("total_tokens");
        assertThat(completed.finishReason()).isEqualTo("stop");
    }

    @Test
    void crossTurnTimelineOrdersByTurnSeqThenMessageSeq() {
        Conversation conv = conversations.save(
            new Conversation(null, "跨轮会话", "conv-5", null, 0, "default", "linmj", Instant.now(), Instant.now()));
        Turn t1 = turns.save(Turn.running(conv.id(), 1));
        Turn t2 = turns.save(Turn.running(conv.id(), 2));

        messages.save(Message.user(t1.id(), 0, "第一轮"));
        messages.save(Message.text(t1.id(), 1, "第一轮回答"));
        messages.save(Message.user(t2.id(), 0, "第二轮"));
        messages.save(Message.text(t2.id(), 1, "第二轮回答"));

        List<Message> timeline = messages.findByConversationIdOrderByTurnIdAscSeqAsc(conv.id());
        assertThat(timeline).extracting(Message::content)
            .containsExactly("第一轮", "第一轮回答", "第二轮", "第二轮回答");
    }
}
