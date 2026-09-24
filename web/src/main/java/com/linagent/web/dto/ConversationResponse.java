package com.linagent.web.dto;

/** 会话列表/创建响应（modes Task 3：带 mode，前端切换器据此回显当前档位） */
public record ConversationResponse(Long id, String title, String mode, int turnCount, String updatedAt) {
}
