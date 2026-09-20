package com.javaagent.web.dto;

public record ConversationResponse(Long id, String title, int turnCount, String updatedAt) {
}
