package com.linagent.agent.persistence;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ConversationRepository extends CrudRepository<Conversation, Long> {

    List<Conversation> findAllByOrderByUpdatedAtDesc();

    @Modifying
    @Query("UPDATE conversation SET updated_at = now() WHERE id = :id")
    void touch(Long id);
}
