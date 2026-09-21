package com.linagent.web.support;

import com.linagent.agent.context.AuthContext;
import org.springframework.http.HttpHeaders;

import java.util.function.Consumer;

/** web 测试默认身份（V4 种子数据） */
public final class TestAuth {

    public static final Consumer<HttpHeaders> LINMJ = h -> {
        h.add("x-tenant-id", "default");
        h.add("x-user-id", "linmj");
    };

    public static final Consumer<HttpHeaders> TESTER = h -> {
        h.add("x-tenant-id", "default");
        h.add("x-user-id", "tester");
    };

    public static final AuthContext LINMJ_CTX = new AuthContext("default", "linmj");

    private TestAuth() {
    }
}
