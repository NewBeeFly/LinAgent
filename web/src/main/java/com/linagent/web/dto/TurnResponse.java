package com.linagent.web.dto;

import com.linagent.agent.persistence.po.Message;
import com.linagent.agent.persistence.po.Turn;

import java.util.List;

public record TurnResponse(Long id, int seq, String status, String finishReason,
                           List<MessageItem> messages) {

    public record MessageItem(int seq, String msgType, String content, String callId,
                              String toolName, String arguments, String result,
                              Boolean success, Long durationMs) {
    }

    public static TurnResponse from(Turn turn, List<Message> messages) {
        return new TurnResponse(turn.id(), turn.seq(), turn.status(), turn.finishReason(),
            messages.stream().map(m -> new MessageItem(m.seq(), m.msgType(), m.content(),
                m.callId(), m.toolName(), m.arguments(), m.result(), m.success(), m.durationMs())).toList());
    }
}
