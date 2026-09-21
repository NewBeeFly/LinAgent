package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.AppUser;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * app_user 只读查询（复合主键 tenant_id+user_id，无持久化路径，新增用户走手工 SQL）。
 * 查询型仓库接口（extends Repository，不继承 CrudRepository）：SQL 全部落在 @Query 注解，
 * 由 Spring Data JDBC 统一管理；ID 泛型参数仅为接口契约占位，无findById。
 */
public interface AppUserRepository extends Repository<AppUser, String> {

    @Query("SELECT tenant_id, user_id, name, created_at FROM app_user "
        + "WHERE tenant_id = :tenantId AND user_id = :userId")
    Optional<AppUser> findByTenantIdAndUserId(String tenantId, String userId);

    /** 身份候选名单（IdentityController 消费），按租户、用户稳定排序 */
    @Query("SELECT tenant_id, user_id, name, created_at FROM app_user ORDER BY tenant_id, user_id")
    List<AppUser> findAll();
}
