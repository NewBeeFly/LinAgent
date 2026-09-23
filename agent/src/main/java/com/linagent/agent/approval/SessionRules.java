package com.linagent.agent.approval;

import java.util.Set;

/**
 * session 作用域审批规则接口（「批准并不再询问·本会话」产生的内存规则）。
 * 引擎只读 {@link #patterns}；{@link #add} 由审批决策路径调用。
 * 实现类 Task 4 交付（InMemorySessionRules：ConcurrentHashMap，key=tenant:user:conv:tool，含 evict）。
 */
public interface SessionRules {

    /** 返回 (tenant, user, conversation, tool) 作用域下的全部 pattern；必须返回空集而非 null */
    Set<String> patterns(String tenantId, String userId, Long conversationId, String toolName);

    /** 追加一条 session 放行 pattern（Set 语义，重复添加幂等） */
    void add(String tenantId, String userId, Long conversationId, String toolName, String pattern);
}
