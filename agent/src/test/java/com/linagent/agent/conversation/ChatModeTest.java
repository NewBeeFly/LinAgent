package com.linagent.agent.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ChatMode.parse 容错契约：合法三档精确解析，null/空白/垃圾值一律回落 STANDARD（永不抛） */
class ChatModeTest {

    @Test
    void parseRecognizesAllThreeModes() {
        assertThat(ChatMode.parse("AUTO")).isEqualTo(ChatMode.AUTO);
        assertThat(ChatMode.parse("STANDARD")).isEqualTo(ChatMode.STANDARD);
        assertThat(ChatMode.parse("CHAT")).isEqualTo(ChatMode.CHAT);
    }

    @Test
    void parseIsCaseInsensitive() {
        assertThat(ChatMode.parse("auto")).isEqualTo(ChatMode.AUTO);
        assertThat(ChatMode.parse("Chat")).isEqualTo(ChatMode.CHAT);
        assertThat(ChatMode.parse("standard")).isEqualTo(ChatMode.STANDARD);
    }

    @Test
    void parseNullFallsBackToStandard() {
        assertThat(ChatMode.parse(null)).isEqualTo(ChatMode.STANDARD);
    }

    @Test
    void parseGarbageFallsBackToStandard() {
        assertThat(ChatMode.parse("")).isEqualTo(ChatMode.STANDARD);
        assertThat(ChatMode.parse("   ")).isEqualTo(ChatMode.STANDARD);
        assertThat(ChatMode.parse("yolo")).isEqualTo(ChatMode.STANDARD);
        assertThat(ChatMode.parse("STANDARD_X")).isEqualTo(ChatMode.STANDARD);
    }
}
