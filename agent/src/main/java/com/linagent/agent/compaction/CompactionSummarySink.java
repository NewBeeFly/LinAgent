package com.linagent.agent.compaction;

/**
 * 压缩摘要产出接缝：跨会话任务接力预留（spec §5.3），MVP 由 LoggingSummarySink 打日志。
 */
public interface CompactionSummarySink {

    void onSummary(SummaryContext ctx);

    record SummaryContext(String threadId, String summary, int messagesBefore, int messagesAfter) {}
}
