package com.javaagent.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.javaagent.agent.facade.AgentFacade;
import com.javaagent.agent.persistence.ConversationRepository;
import com.javaagent.agent.persistence.MessageRepository;
import com.javaagent.agent.persistence.TurnRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConversationController.class)
class ConversationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;

    @Test
    void createReturnsConversation() throws Exception {
        when(conversations.save(any())).thenReturn(
            new com.javaagent.agent.persistence.Conversation(1L, "新会话", "conv-1", null,
                java.time.Instant.now(), java.time.Instant.now()));

        mockMvc.perform(post("/api/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新会话\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.title").value("新会话"));
    }

    @Test
    void listReturnsConversationsWithTurnCount() throws Exception {
        when(conversations.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(
            new com.javaagent.agent.persistence.Conversation(1L, "会话A", "conv-1", null,
                java.time.Instant.now(), java.time.Instant.now())));
        when(turns.countByConversationId(1L)).thenReturn(3);

        mockMvc.perform(get("/api/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].turnCount").value(3));
    }

    @Test
    void turnsReplayReturnsNestedMessages() throws Exception {
        when(conversations.findById(1L)).thenReturn(java.util.Optional.of(
            new com.javaagent.agent.persistence.Conversation(1L, "会话A", "conv-1", null,
                java.time.Instant.now(), java.time.Instant.now())));
        when(turns.findByConversationIdOrderBySeqAsc(1L)).thenReturn(List.of(
            new com.javaagent.agent.persistence.Turn(10L, 1L, 1, "COMPLETED", "STOP", null,
                java.time.Instant.now(), java.time.Instant.now())));
        when(messages.findByTurnIdOrderBySeq(10L)).thenReturn(List.of(
            new com.javaagent.agent.persistence.Message(1L, 10L, 0, "USER", "问题", null, null, null, null, null, null, java.time.Instant.now()),
            new com.javaagent.agent.persistence.Message(2L, 10L, 1, "TEXT", "回答", null, null, null, null, null, null, java.time.Instant.now())));

        mockMvc.perform(get("/api/conversations/1/turns"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].messages[0].msgType").value("USER"))
            .andExpect(jsonPath("$[0].messages[1].msgType").value("TEXT"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/conversations/1"))
            .andExpect(status().isNoContent());
    }
}
