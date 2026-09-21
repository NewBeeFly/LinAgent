package com.linagent.web.auth;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 身份入口：/api/* 读取 x-tenant-id / x-user-id，经 RequestAuthenticator 校验后
 * 写入 ThreadLocal。401 短路不再进 DispatcherServlet。
 * 清理三保险：开头防御性 clear（防池化线程残留）+ finally clear + require() 兜底。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthContextFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "x-tenant-id";
    static final String USER_HEADER = "x-user-id";
    /** 身份候选名单路径：免鉴权（切换器需在任何身份生效前拿到候选）。登录体系上线时收紧 */
    static final String IDENTITY_OPTIONS_PATH = "/api/identity/options";

    private final RequestAuthenticator authenticator;

    public AuthContextFilter(RequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (IDENTITY_OPTIONS_PATH.equals(uri)) {
            return true;
        }
        return !uri.startsWith("/api");
    }

    /**
     * SSE 的 ASYNC dispatch 不再二次鉴权（显式固定 OncePerRequestFilter 的默认行为，
     * 防默认变化或子类覆写后 ASYNC 重入在响应已提交后误写 401）。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuthContextHolder.clear();
        try {
            String tenantId = request.getHeader(TENANT_HEADER);
            String userId = request.getHeader(USER_HEADER);
            if (isBlank(tenantId) || isBlank(userId)) {
                writeUnauthorized(response, "缺少身份请求头 x-tenant-id / x-user-id");
                return;
            }
            Optional<AuthContext> authenticated = authenticator.authenticate(tenantId, userId);
            if (authenticated.isEmpty()) {
                writeUnauthorized(response, "未知身份: " + tenantId + "/" + userId);
                return;
            }
            AuthContextHolder.set(authenticated.get());
            filterChain.doFilter(request, response);
        } finally {
            AuthContextHolder.clear();
        }
    }

    private boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\": \"" + message + "\"}");
    }
}
