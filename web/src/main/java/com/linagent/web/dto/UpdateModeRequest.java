package com.linagent.web.dto;

/** PUT /api/conversations/{id}/mode 请求体：严格枚举校验由控制器承担（非法值 400，不静默回落） */
public record UpdateModeRequest(String mode) {
}
