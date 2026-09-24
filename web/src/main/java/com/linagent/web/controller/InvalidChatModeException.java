package com.linagent.web.controller;

/** PUT /mode 请求体非三档合法值（AUTO/STANDARD/CHAT）——本端点严格校验，web 层映射 400 */
public class InvalidChatModeException extends RuntimeException {

    public InvalidChatModeException(String raw) {
        super("无效的会话模式: " + raw + "（合法值：AUTO/STANDARD/CHAT）");
    }
}
