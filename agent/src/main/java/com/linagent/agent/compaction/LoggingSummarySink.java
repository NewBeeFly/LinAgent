package com.linagent.agent.compaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** MVP 默认实现：仅日志。跨会话任务接力在此扩展（spec §5.3 预留）。 */
@Component
public class LoggingSummarySink implements CompactionSummarySink {

    private static final Logger log = LoggerFactory.getLogger(LoggingSummarySink.class);

    @Override
    public void onSummary(SummaryContext ctx) {
        // 摘要全文进日志：压缩产物唯一可观测出口（checkpoint 里仅序列化在 state 内，业务表不落）
        log.info("""
            [compaction] summary produced: threadId={} messages {}→{} summaryChars={}
            --- 压缩摘要全文 ---
            {}
            --- 摘要结束 ---""",
            ctx.threadId(), ctx.messagesBefore(), ctx.messagesAfter(), ctx.summary().length(),
            ctx.summary());
    }
}
