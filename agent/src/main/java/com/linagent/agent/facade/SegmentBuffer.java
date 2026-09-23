package com.linagent.agent.facade;

import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.repository.MessageRepository;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 增量累积 → 段落边界 flush 完整 Message（spec：不存 delta，存完整消息）。
 * seq=0 固定为 USER 消息（由 facade 在创建 buffer 前落库），内部从 1 起。
 *
 * 线程安全（fix round 1）：side 流的 appendThinking、main 流的 appendText、
 * 工具拦截器的 recordToolCall/recordToolResult（SAA 并行 tool call 时为多线程）
 * 会并发写同一 buffer——所有变更方法 synchronized（监视器为 this，临界区极小），
 * seq 用 AtomicInteger；recordToolCall 的 flushThinking+flushText+save 在同一
 * 临界区内，保证 flush 与 seq 分配的原子性（避免段落与工具消息 seq 交错乱序）。
 */
public class SegmentBuffer {

    /** 工具结果落库截断上限，避免超长输出撑爆 content 列 */
    static final int TOOL_RESULT_MAX_LENGTH = 10_000;
    private static final String TRUNCATE_SUFFIX = "\n...[截断]";

    private final Long turnId;
    private final MessageRepository repository;
    private final AtomicInteger seq;
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder text = new StringBuilder();

    public SegmentBuffer(Long turnId, MessageRepository repository) {
        this(turnId, repository, 1);
    }

    /** resume 续跑（Task 6）：seq 从既有落库行的最大 seq 之后继续（不与中断前已落行冲突） */
    public SegmentBuffer(Long turnId, MessageRepository repository, int startSeq) {
        this.turnId = turnId;
        this.repository = repository;
        this.seq = new AtomicInteger(startSeq);
    }

    public synchronized void appendThinking(String delta) {
        thinking.append(delta);
    }

    public synchronized void appendText(String delta) {
        text.append(delta);
    }

    public synchronized void flushThinking() {
        if (!thinking.isEmpty()) {
            repository.save(Message.thinking(turnId, nextSeq(), thinking.toString()));
            thinking.setLength(0);
        }
    }

    public synchronized void flushText() {
        if (!text.isEmpty()) {
            repository.save(Message.text(turnId, nextSeq(), text.toString()));
            text.setLength(0);
        }
    }

    /** 工具调用边界：flush 残留段 + 落 TOOL_CALL 必须原子（同一临界区），否则 seq 交错 */
    public synchronized void recordToolCall(String callId, String toolName, String arguments) {
        flushThinking();
        flushText();
        repository.save(Message.toolCall(turnId, nextSeq(), callId, toolName, arguments));
    }

    public synchronized void recordToolResult(String callId, String toolName, String result,
                                              boolean success, long durationMs) {
        repository.save(Message.toolResult(turnId, nextSeq(), callId, toolName,
            truncate(result), success, durationMs));
    }

    public synchronized void flushAll() {
        flushThinking();
        flushText();
    }

    /** 当前下一个可用 seq（不递增）：ERROR 消息落库时使用 */
    public synchronized int currentSeq() {
        return seq.get();
    }

    private int nextSeq() {
        return seq.getAndIncrement();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > TOOL_RESULT_MAX_LENGTH
            ? s.substring(0, TOOL_RESULT_MAX_LENGTH) + TRUNCATE_SUFFIX
            : s;
    }
}
