package com.linagent.agent.persistence.po;

import org.springframework.data.relational.core.mapping.Table;

/**
 * graphthread 只读投影（SAA PostgresSaver 的 checkpoint 线程表，非本工程聚合）。
 * 仅作为 GraphThreadRepository 的 PersistentEntity 载体存在，无持久化写入路径。
 */
@Table("graphthread")
public record GraphThread(String threadName) {
}
