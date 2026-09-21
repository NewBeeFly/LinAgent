package com.linagent.web.auth;

import com.linagent.agent.context.AuthContext;

import java.util.Optional;

/**
 * 鉴权抽象（本期唯一扩展点）：请求身份 → AuthContext。
 * 本期实现 HeaderUserAuthenticator（app_user 表校验）；
 * 后续登录态/token 换实现，Filter 与消费方不动。
 */
public interface RequestAuthenticator {

    Optional<AuthContext> authenticate(String tenantId, String userId);
}
