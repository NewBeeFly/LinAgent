package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.GraphThread;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * graphthread（SAA PostgresSaver 的 checkpoint 线程表，非本工程聚合）的清理仓库。
 * 查询型接口：SQL 落在 @Modifying @Query 注解，由 Spring Data JDBC 统一管理；
 * graphcheckpoint 经 FK ON DELETE CASCADE 随 graphthread 行级联删除。
 */
public interface GraphThreadRepository extends Repository<GraphThread, String> {

    /**
     * 删除指定会话的全部 checkpoint 线程。
     * threadId 模式：conv-{id}（原始线程）与 conv-{id}-v*（压缩换代线程）。
     */
    @Modifying
    @Query("DELETE FROM graphthread WHERE thread_name = :baseThreadId OR thread_name LIKE :versionedPrefix")
    int deleteByThreadPrefix(String baseThreadId, String versionedPrefix);
}
