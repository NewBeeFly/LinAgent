package com.linagent.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthContextHolderTest {

    @AfterEach
    void clean() {
        AuthContextHolder.clear();
    }

    @Test
    void setThenGetThenClear() {
        AuthContext ctx = new AuthContext("default", "linmj");
        AuthContextHolder.set(ctx);
        assertThat(AuthContextHolder.get()).isSameAs(ctx);
        assertThat(AuthContextHolder.require()).isSameAs(ctx);
        AuthContextHolder.clear();
        assertThat(AuthContextHolder.get()).isNull();
    }

    @Test
    void requireThrowsWhenMissing() {
        assertThatThrownBy(AuthContextHolder::require)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AuthContext");
    }
}
