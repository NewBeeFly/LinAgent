package com.linagent.web.dto;

/** PUT /api/conversations/{id}/mode 响应：仅回切档结果（规范化后的枚举名） */
public record ModeResponse(Long id, String mode) {
}
