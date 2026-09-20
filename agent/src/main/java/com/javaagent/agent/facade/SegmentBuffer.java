package com.javaagent.agent.facade;

import com.javaagent.agent.persistence.Message;
import com.javaagent.agent.persistence.MessageRepository;

/**
 * 增量累积 → 段落边界 flush 完整 Message（spec：不存 delta，存完整消息）。
 * seq=0 固定为 USER 消息（由 facade 在创建 buffer 前落库），内部从 1 起。
 */
public class SegmentBuffer {

    /** 工具结果落库截断上限，避免超长输出撑爆 content 列 */
    static final int TOOL_RESULT_MAX_LENGTH = 10_000;
    private static final String TRUNCATE_SUFFIX = "\n...[截断]";

    private final Long turnId;
    private final MessageRepository repository;
    private int seq;
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder text = new StringBuilder();

    public SegmentBuffer(Long turnId, MessageRepository repository) {
        this.turnId = turnId;
        this.repository = repository;
        this.seq = 1;
    }

    public void appendThinking(String delta) {
        thinking.append(delta);
    }

    public void appendText(String delta) {
        text.append(delta);
    }

    public void flushThinking() {
        if (!thinking.isEmpty()) {
            repository.save(Message.thinking(turnId, nextSeq(), thinking.toString()));
            thinking.setLength(0);
        }
    }

    public void flushText() {
        if (!text.isEmpty()) {
            repository.save(Message.text(turnId, nextSeq(), text.toString()));
            text.setLength(0);
        }
    }

    public void recordToolCall(String callId, String toolName, String arguments) {
        flushThinking();
        flushText();
        repository.save(Message.toolCall(turnId, nextSeq(), callId, toolName, arguments));
    }

    public void recordToolResult(String callId, String toolName, String result,
                                 boolean success, long durationMs) {
        repository.save(Message.toolResult(turnId, nextSeq(), callId, toolName,
            truncate(result), success, durationMs));
    }

    public void flushAll() {
        flushThinking();
        flushText();
    }

    /** 当前下一个可用 seq（不递增）：ERROR 消息落库时使用 */
    public int currentSeq() {
        return seq;
    }

    private int nextSeq() {
        return seq++;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > TOOL_RESULT_MAX_LENGTH
            ? s.substring(0, TOOL_RESULT_MAX_LENGTH) + TRUNCATE_SUFFIX
            : s;
    }
}
