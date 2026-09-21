package com.linagent.agent.context;

/**
 * 请求身份上下文（业务概念，非 HTTP 概念，故归 agent 模块）。
 * 由 web 层鉴权入口赋值，经 ThreadLocal 边界运输，入口同步段读出后显式传参。
 */
public record AuthContext(String tenantId, String userId, String name) {
}
