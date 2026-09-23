package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.Turn;

import org.springframework.data.repository.CrudRepository;

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
}
