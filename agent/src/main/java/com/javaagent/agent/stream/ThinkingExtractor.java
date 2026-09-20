package com.javaagent.agent.stream;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.Optional;

public interface ThinkingExtractor {
    Optional<String> thinkingDelta(ChatResponse response);
    Optional<String> textDelta(ChatResponse response);
}
