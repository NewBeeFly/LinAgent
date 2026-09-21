package com.linagent.agent.persistence;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface MessageRepository extends CrudRepository<Message, Long> {

    List<Message> findByTurnIdOrderBySeq(Long turnId);

    @Query("SELECT m.* FROM message m JOIN turn t ON m.turn_id = t.id " +
           "WHERE t.conversation_id = :conversationId ORDER BY t.seq ASC, m.seq ASC")
    List<Message> findByConversationIdOrderByTurnIdAscSeqAsc(Long conversationId);
}
