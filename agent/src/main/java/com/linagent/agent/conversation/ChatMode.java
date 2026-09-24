package com.linagent.agent.conversation;

import java.util.Locale;

/**
 * 会话模式（v0.3）：会话级持久，存 conversation.mode 列（VARCHAR(16)）。
 * <ul>
 *   <li>{@link #AUTO}——智能路由：由模型按消息形态决定是否启用工具（构造 ReactAgent 时分支）</li>
 *   <li>{@link #STANDARD}——标准 Agent：全量工具（默认档，存量/新会话缺省）</li>
 *   <li>{@link #CHAT}——纯聊天：无工具</li>
 * </ul>
 */
public enum ChatMode {
    AUTO, STANDARD, CHAT;

    /** 缺省档（V8 列 DEFAULT / parse 回落值 / Conversation.create 工厂默认） */
    public static final ChatMode DEFAULT = STANDARD;

    /**
     * 容错解析：null/空白/未知值一律回落 {@link #STANDARD}，永不抛异常；
     * 大小写不敏感（输入源头是 DB 列与外部请求体，属边界输入，判空与容错是实证政策内的必要防御）。
     */
    public static ChatMode parse(String s) {
        if (s == null || s.isBlank()) {
            return DEFAULT;
        }
        try {
            return ChatMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
