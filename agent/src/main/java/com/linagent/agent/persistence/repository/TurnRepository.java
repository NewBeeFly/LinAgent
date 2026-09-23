package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.Turn;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TurnRepository extends CrudRepository<Turn, Long> {

    int countByConversationId(Long conversationId);

    /** 审批未决前置检查（Task 5）：存在 WAITING_APPROVAL 轮的会话拒绝新消息（409 语义） */
    boolean existsByConversationIdAndStatus(Long conversationId, String status);

    /** 待决议轮定位（Task 6 resume/GET pending）：最新一条指定状态轮（同会话同时至多一条未决轮） */
    Optional<Turn> findTopByConversationIdAndStatusOrderBySeqDesc(Long conversationId, String status);

    Optional<Turn> findTopByConversationIdOrderBySeqDesc(Long conversationId);

    List<Turn> findByConversationIdOrderBySeqAsc(Long conversationId);

    /**
     * 并发占轮（终审 I1）：原子抢占待决议轮——仅当 status 仍为 WAITING_APPROVAL 时置
     * RUNNING 并返回 1，否则返回 0。两个并发 POST /approvals 同时通过 pending 预检时，
     * 只有一个请求 claim 成功（赢者续跑），输者据此判 409——杜绝从同一 checkpoint
     * 双恢复导致已批准工具执行两次（不可恢复副作用）。@Modifying 需事务，调用方
     * {@code AgentFacade.resume} 已挂 @Transactional。
     */
    @Modifying
    @Query("UPDATE turn SET status = 'RUNNING' WHERE id = :id AND status = 'WAITING_APPROVAL'")
    int claimWaitingTurn(@Param("id") Long id);
}
