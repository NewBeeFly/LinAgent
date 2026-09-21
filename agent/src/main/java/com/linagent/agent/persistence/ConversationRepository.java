package com.linagent.agent.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends CrudRepository<Conversation, Long> {

    List<Conversation> findAllByOrderByUpdatedAtDesc();

    /**
     * 多租户 v0.2（Task 3 先声明，Task 4 控制器切换调用）。
     * 实证（2026-09-21，SD JDBC 3.5.1）：派生查询在实体无 tenantId/userId 字段时
     * 【启动期】即报 No property（仓储 Bean 创建时就构建派生查询，非运行期才报错），
     * 故先用显式 @Query 落真实 SQL——conversation 归属列 V5 才加，V5 前本方法仅被
     * mock 测试消费不触真实 SQL；Task 4 补实体字段后语义即完整成立。
     */
    @Query("SELECT * FROM conversation WHERE tenant_id = :tenantId AND user_id = :userId "
        + "ORDER BY updated_at DESC")
    List<Conversation> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);

    /** 单查按归属收敛：实体补齐归属字段后（Task 4），派生查询即可用（Task 3 实证） */
    Optional<Conversation> findByIdAndTenantIdAndUserId(Long id, String tenantId, String userId);

    @Modifying
    @Query("UPDATE conversation SET updated_at = now() WHERE id = :id")
    void touch(Long id);
}
