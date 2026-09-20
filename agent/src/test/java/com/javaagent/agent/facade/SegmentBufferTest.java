package com.javaagent.agent.facade;

import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentBufferTest {

    static class RecordingRepo implements MessageRepository {
        List<Message> saved = new ArrayList<>();
        @Override public <S extends Message> S save(S entity) { saved.add(entity); return entity; }
        @Override public <S extends Message> List<S> saveAll(Iterable<S> entities) {
            entities.forEach(saved::add);
            return List.copyOf((List<S>) entities);
        }
        @Override public java.util.Optional<Message> findById(Long aLong) { return java.util.Optional.empty(); }
        @Override public boolean existsById(Long aLong) { return false; }
        @Override public List<Message> findAll() { return List.of(); }
        @Override public List<Message> findAllById(Iterable<Long> ids) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public void deleteById(Long aLong) {}
        @Override public void delete(Message entity) {}
        @Override public void deleteAllById(Iterable<? extends Long> ids) {}
        @Override public void deleteAll(Iterable<? extends Message> entities) {}
        @Override public void deleteAll() {}
        @Override public List<Message> findByTurnIdOrderBySeq(Long turnId) { return List.of(); }
        @Override public List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId) { return List.of(); }
    }

    @Test
    void thinkingAccumulatesAndFlushesAsOneMessage() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.appendThinking("思考A");
        buffer.appendThinking("思考B");
        buffer.flushThinking();  // 段落边界

        assertThat(repo.saved).hasSize(1);
        Message saved = repo.saved.get(0);
        assertThat(saved.msgType()).isEqualTo("THINKING");
        assertThat(saved.content()).isEqualTo("思考A思考B");
        assertThat(saved.seq()).isEqualTo(1);  // seq=0 是 USER
    }

    @Test
    void newThinkingSegmentAfterToolUsesPreviousSeq() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.appendThinking("第一段");
        buffer.flushThinking();
        buffer.recordToolCall("call-1", "list_dir", "{}");
        buffer.appendThinking("第二段");
        buffer.flushThinking();

        assertThat(repo.saved).extracting(Message::seq).containsExactly(1, 2, 3);
        assertThat(repo.saved).extracting(Message::msgType)
            .containsExactly("THINKING", "TOOL_CALL", "THINKING");
    }

    @Test
    void toolResultIsSavedImmediately() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.recordToolResult("call-1", "list_dir", "结果", true, 100L);

        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).success()).isTrue();
        assertThat(repo.saved.get(0).durationMs()).isEqualTo(100L);
    }

    @Test
    void emptySegmentsAreNotSaved() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.flushThinking();
        buffer.flushText();

        assertThat(repo.saved).isEmpty();
    }

    @Test
    void flushAllWritesPendingSegments() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        buffer.appendText("正文");
        buffer.flushAll();

        assertThat(repo.saved).hasSize(1);
        assertThat(repo.saved.get(0).msgType()).isEqualTo("TEXT");
    }

    @Test
    void currentSeqReturnsNextSeqWithoutIncrementing() {
        RecordingRepo repo = new RecordingRepo();
        SegmentBuffer buffer = new SegmentBuffer(42L, repo);
        assertThat(buffer.currentSeq()).isEqualTo(1);
        buffer.appendThinking("x");
        buffer.flushThinking();
        assertThat(buffer.currentSeq()).isEqualTo(2);
    }
}
