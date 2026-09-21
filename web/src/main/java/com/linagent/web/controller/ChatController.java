package com.linagent.web.controller;

import com.linagent.agent.facade.AgentFacade;
import com.linagent.web.dto.ChatRequest;
import com.linagent.web.stream.SseEventMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * SSE 流式对话：前端断连时 Flux 取消沿 toSse -> agentFacade.chat 向上游传播
 * （facade 的 doFinally 会收到 CANCEL，落库收尾在 agent 模块内处理，web 层纯透传）。
 */
@RestController
public class ChatController {

    private final AgentFacade agentFacade;
    private final SseEventMapper sseEventMapper;

    public ChatController(AgentFacade agentFacade, SseEventMapper sseEventMapper) {
        this.agentFacade = agentFacade;
        this.sseEventMapper = sseEventMapper;
    }

    @PostMapping(value = "/api/conversations/{conversationId}/chat",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@PathVariable Long conversationId,
                                              @Valid @RequestBody ChatRequest request) {
        return sseEventMapper.toSse(agentFacade.chat(conversationId, request.content()));
    }
}
