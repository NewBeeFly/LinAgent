package com.javaagent.agent.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.javaagent.agent.facade.AgentEvent;
import com.javaagent.agent.facade.SegmentBuffer;
import reactor.core.publisher.Sinks;

/**
 * 工具事件直发 + 工具错误兜底（spec §7：失败不中断 ReAct 循环，回传错误文本给模型）。
 * 事件与落库同源：interceptToolCall 里先落 buffer（TOOL_CALL/TOOL_RESULT 消息），
 * 再向 sideEvents 发 AgentEvent.ToolCall / ToolResult —— facade 对这些事件仅透传。
 */
public class EventEmittingToolInterceptor extends ToolInterceptor {

    private final Sinks.Many<Object> eventSink;
    private final SegmentBuffer buffer;
    private final Long turnId;

    public EventEmittingToolInterceptor(Sinks.Many<Object> eventSink, SegmentBuffer buffer, Long turnId) {
        this.eventSink = eventSink;
        this.buffer = buffer;
        this.turnId = turnId;
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String callId = request.getToolCallId();
        String toolName = request.getToolName();
        String arguments = request.getArguments();

        buffer.recordToolCall(callId, toolName, arguments);
        eventSink.tryEmitNext(new AgentEvent.ToolCall(turnId, callId, toolName, arguments));

        long start = System.currentTimeMillis();
        try {
            ToolCallResponse response = handler.call(request);
            long duration = System.currentTimeMillis() - start;
            String result = response.getResult();
            buffer.recordToolResult(callId, toolName, result, true, duration);
            eventSink.tryEmitNext(new AgentEvent.ToolResult(
                turnId, callId, toolName, result, duration, true));
            return response;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String message = "Tool failed: " + e.getMessage();
            buffer.recordToolResult(callId, toolName, message, false, duration);
            eventSink.tryEmitNext(new AgentEvent.ToolResult(
                turnId, callId, toolName, message, duration, false));
            // 回传错误文本给模型，ReAct 循环继续（与 SAA ToolErrorInterceptor 同款约定）
            return ToolCallResponse.of(callId, toolName, message);
        }
    }

    @Override
    public String getName() {
        return "EventEmittingToolInterceptor";
    }
}
