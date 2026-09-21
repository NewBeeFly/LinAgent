package com.linagent.agent.persistence.repository;

import com.linagent.agent.persistence.po.Turn;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface TurnRepository extends CrudRepository<Turn, Long> {

    int countByConversationId(Long conversationId);

    Optional<Turn> findTopByConversationIdOrderBySeqDesc(Long conversationId);

    List<Turn> findByConversationIdOrderBySeqAsc(Long conversationId);
}
