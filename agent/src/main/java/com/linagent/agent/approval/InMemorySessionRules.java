package com.linagent.agent.approval;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SessionRules} 内存实现：「批准并不再询问·本会话」产生的规则只活在本次进程内。
 * key = {@code tenant:user:conv:tool} 四段（tenant/user 约定不含 ':'，与引擎测试替身同构）。
 * 引擎读 {@link #patterns}；审批决策路径调 {@link #add}；会话收尾调 {@link #evict} 清理。
 */
@Component
public class InMemorySessionRules implements SessionRules {

    private final ConcurrentHashMap<String, Set<String>> rules = new ConcurrentHashMap<>();

    private static String key(String tenantId, String userId, Long conversationId, String toolName) {
        return tenantId + ":" + userId + ":" + conversationId + ":" + toolName;
    }

    @Override
    public Set<String> patterns(String tenantId, String userId, Long conversationId, String toolName) {
        // 返回不可变快照：读侧遍历不受并发 add/evict 影响
        return Set.copyOf(rules.getOrDefault(key(tenantId, userId, conversationId, toolName), Set.of()));
    }

    @Override
    public void add(String tenantId, String userId, Long conversationId, String toolName, String pattern) {
        rules.computeIfAbsent(key(tenantId, userId, conversationId, toolName), k -> ConcurrentHashMap.newKeySet())
                .add(pattern);
    }

    /** 会话结束清理：移除该 conversationId 下全部工具的 session 规则（key 第三段匹配，跨租户/用户） */
    public void evict(Long conversationId) {
        String conv = String.valueOf(conversationId);
        rules.keySet().removeIf(k -> {
            String[] parts = k.split(":", 4);
            return parts.length == 4 && parts[2].equals(conv);
        });
    }
}
