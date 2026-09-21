package com.linagent.agent.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话删除时级联清理 graph checkpoint（PostgresSaver 的 graphthread/graphcheckpoint 表）。
 * 落在 agent 模块：checkpoint 存储结构（表名/键）属于 saver 的实现细节，web 层只透传调用。
 */
@Component
public class CheckpointCleaner {

    private final JdbcTemplate jdbcTemplate;

    public CheckpointCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 删除指定会话的全部 checkpoint 线程。
     * threadId 模式：conv-{id}（原始线程）与 conv-{id}-v*（压缩换代线程）；
     * graphcheckpoint 经 FK ON DELETE CASCADE 随 graphthread 行级联删除。
     */
    public void deleteByConversationId(Long conversationId) {
        String baseThreadId = "conv-" + conversationId;
        jdbcTemplate.update(
            "DELETE FROM graphthread WHERE thread_name = ? OR thread_name LIKE ?",
            baseThreadId, baseThreadId + "-v%");
    }
}
