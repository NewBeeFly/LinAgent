package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.PermissionRule;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface PermissionRuleRepository extends CrudRepository<PermissionRule, Long> {

    /** 用户作用域规则：规则引擎评估序（白名单 → session → user → 审批）的 user 层取数 */
    List<PermissionRule> findByTenantIdAndUserIdAndEffect(String tenantId, String userId, String effect);

    /** 删除规则按 (tenantId, userId, id) 三元收敛，非属主删除不生效 */
    void deleteByTenantIdAndUserIdAndId(String tenantId, String userId, Long id);

    /** 唯一约束 (tenant_id, user_id, tool_name, pattern) 的落库预检：存在则不重复写 */
    boolean existsByTenantIdAndUserIdAndToolNameAndPattern(String tenantId, String userId,
                                                           String toolName, String pattern);
}
