package com.linagent.agent.context;

/**
 * ThreadLocal 边界运输容器。纪律：只有鉴权入口（Filter）set；
 * require() 只允许出现在 controller 方法体与 facade.chat() 的 defer 外同步段；
 * reactor 线程零 ThreadLocal 依赖（订阅期清理已完成且线程不同，defer 内读取必炸）。
 */
public final class AuthContextHolder {

    private static final ThreadLocal<AuthContext> CTX = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    public static void set(AuthContext ctx) {
        CTX.set(ctx);
    }

    public static AuthContext get() {
        return CTX.get();
    }

    /** 缺失即抛：任何越界读取第一时间暴露而非静默串号 */
    public static AuthContext require() {
        AuthContext ctx = CTX.get();
        if (ctx == null) {
            throw new IllegalStateException(
                "AuthContext 缺失：未经鉴权入口，或在异步线程读取（ThreadLocal 仅入口同步段有效）");
        }
        return ctx;
    }

    public static void clear() {
        CTX.remove();
    }
}
