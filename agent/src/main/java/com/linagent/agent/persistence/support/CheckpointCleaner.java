package com.linagent.agent.persistence.support;

import com.linagent.agent.persistence.repository.GraphThreadRepository;

import org.springframework.stereotype.Component;

/**
 * 会话删除时级联清理 graph checkpoint（PostgresSaver 的 graphthread/graphcheckpoint 表）。
 * 落在 agent 模块：checkpoint 存储结构（表名/键）属于 saver 的实现细节，web 层只透传调用。
 * SQL 由 GraphThreadRepository 的 @Modifying @Query 承载，本类只保留业务语义。
 */
@Component
public class CheckpointCleaner {

    private final GraphThreadRepository graphThreads;

    public CheckpointCleaner(GraphThreadRepository graphThreads) {
        this.graphThreads = graphThreads;
    }

    /**
     * 删除指定会话的全部 checkpoint 线程（幂等：未知会话为 0 行，不报错）。
     * threadId 模式：conv-{id}（原始线程）与 conv-{id}-v*（压缩换代线程）。
     */
    public void deleteByConversationId(Long conversationId) {
        String baseThreadId = "conv-" + conversationId;
        graphThreads.deleteByThreadPrefix(baseThreadId, baseThreadId + "-v%");
    }
}
