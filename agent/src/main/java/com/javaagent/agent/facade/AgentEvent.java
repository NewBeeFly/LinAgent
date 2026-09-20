package com.javaagent.agent.facade;

/**
 * agent 引擎对外统一事件流（框架无关，web 层转 SSE）。
 */
public sealed interface AgentEvent {

    Long turnId();

    record Meta(Long turnId, Long conversationId, String model) implements AgentEvent {}

    record ThinkingDelta(Long turnId, String content) implements AgentEvent {}

    record MessageDelta(Long turnId, String content) implements AgentEvent {}

    record ToolCall(Long turnId, String callId, String toolName, String arguments) implements AgentEvent {}

    record ToolResult(Long turnId, String callId, String toolName, String result,
                      long durationMs, boolean success) implements AgentEvent {}

    record TurnDone(Long turnId, String finishReason, Usage usage) implements AgentEvent {}

    record TurnError(Long turnId, String code, String message) implements AgentEvent {}

    record Usage(long promptTokens, long completionTokens, long totalTokens) {}
}
