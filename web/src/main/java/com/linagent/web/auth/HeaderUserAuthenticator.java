package com.linagent.web.auth;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.persistence.AppUserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** header 身份 + app_user 表校验（本期唯一实现） */
@Component
public class HeaderUserAuthenticator implements RequestAuthenticator {

    private final AppUserRepository users;

    public HeaderUserAuthenticator(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<AuthContext> authenticate(String tenantId, String userId) {
        return users.findByTenantIdAndUserId(tenantId, userId)
            .map(u -> new AuthContext(u.tenantId(), u.userId()));
    }
}
