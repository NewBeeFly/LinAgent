package com.linagent.web.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagent.agent.approval.PermissionRuleEngine;
import com.linagent.agent.facade.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ApprovalRequest → SSE 协议映射（Task 5）：事件名 approval_request，data 携带 items JSON。 */
class SseEventMapperTest {

    private final SseEventMapper mapper = new SseEventMapper(new ObjectMapper());

    @Test
    void approvalRequestMapsToApprovalRequestEventWithItemsJson() {
        PermissionRuleEngine.PendingItem item = new PermissionRuleEngine.PendingItem(
            "call-9", "shell", "{\"command\":\"rm -rf /tmp/x\"}", "rm -rf /tmp/x",
            List.of(new PermissionRuleEngine.SubVerdict("rm -rf /tmp/x", false, null)), "rm *");

        ServerSentEvent<String> sse = mapper.toSse(
            new AgentEvent.ApprovalRequest(10L, 1L, List.of(item)), 3);

        assertThat(sse.event()).isEqualTo("approval_request");
        assertThat(sse.data())
            .contains("\"turnId\":10")
            .contains("\"conversationId\":1")
            // PendingItem record 字段整体序列化（前端审批卡片直接消费）
            .contains("\"callId\":\"call-9\"")
            .contains("\"toolName\":\"shell\"")
            .contains("\"arguments\":\"{\\\"command\\\":\\\"rm -rf /tmp/x\\\"}\"")
            .contains("\"suggestedRule\":\"rm *\"")
            .contains("\"seq\":3");
    }
}
