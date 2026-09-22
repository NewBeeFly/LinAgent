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
        log.info("[compaction] summary produced: threadId={} messages {}→{} summaryChars={}",
            ctx.threadId(), ctx.messagesBefore(), ctx.messagesAfter(), ctx.summary().length());
    }
}
