package com.linagent.web.controller;

import com.linagent.agent.approval.InMemorySessionRules;
import com.linagent.agent.facade.AgentFacade;
import com.linagent.agent.persistence.po.PermissionRule;
import com.linagent.agent.persistence.repository.ConversationRepository;
import com.linagent.agent.persistence.repository.MessageRepository;
import com.linagent.agent.persistence.repository.PermissionRuleRepository;
import com.linagent.agent.persistence.repository.TurnRepository;
import com.linagent.web.auth.RequestAuthenticator;
import com.linagent.web.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * permission-rules CRUD 协议测试（Task 6 brief Step 1）：当前身份过滤、
 * 工具白名单/pattern 校验（400）、重复规则 409、删除按 (tenant,user,id) 收敛。
 */
@WebMvcTest(PermissionRuleController.class)
class PermissionRuleControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean com.linagent.agent.persistence.repository.AppUserRepository appUsers;
    @MockBean com.linagent.agent.persistence.repository.GraphThreadRepository graphThreads;
    @MockBean AgentFacade agentFacade;
    @MockBean ConversationRepository conversations;
    @MockBean TurnRepository turns;
    @MockBean MessageRepository messages;
    @MockBean PermissionRuleRepository permissionRules;
    @MockBean InMemorySessionRules sessionRules;
    @MockBean RequestAuthenticator authenticator;

    private final HttpHeaders authHeaders = buildAuthHeaders();

    private static HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        TestAuth.LINMJ.accept(headers);
        return headers;
    }

    @BeforeEach
    void auth() {
        when(authenticator.authenticate("default", "linmj"))
            .thenReturn(java.util.Optional.of(TestAuth.LINMJ_CTX));
    }

    @Test
    void listReturnsRulesScopedToCurrentIdentity() throws Exception {
        when(permissionRules.findByTenantIdAndUserIdAndEffect("default", "linmj", "ALLOW"))
            .thenReturn(List.of(new PermissionRule(7L, "default", "linmj", "shell",
                "pip install *", "ALLOW", Instant.parse("2026-09-23T00:00:00Z"))));

        mockMvc.perform(get("/api/permission-rules").headers(authHeaders))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(7))
            .andExpect(jsonPath("$[0].toolName").value("shell"))
            .andExpect(jsonPath("$[0].pattern").value("pip install *"))
            .andExpect(jsonPath("$[0].effect").value("ALLOW"));
    }

    @Test
    void createPersistsAllowRuleForCurrentIdentity() throws Exception {
        when(permissionRules.save(any())).thenAnswer(inv -> {
            PermissionRule e = inv.getArgument(0);
            return new PermissionRule(9L, e.tenantId(), e.userId(), e.toolName(),
                e.pattern(), e.effect(), e.createdAt());
        });

        mockMvc.perform(post("/api/permission-rules").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"shell\",\"pattern\":\"pip install *\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(9))
            .andExpect(jsonPath("$.pattern").value("pip install *"));

        org.mockito.ArgumentCaptor<PermissionRule> captor =
            org.mockito.ArgumentCaptor.forClass(PermissionRule.class);
        verify(permissionRules).save(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("default");
        assertThat(captor.getValue().userId()).isEqualTo("linmj");
        assertThat(captor.getValue().effect()).isEqualTo("ALLOW");
    }

    @Test
    void createRejectsUnknownToolAndBlankPattern() throws Exception {
        mockMvc.perform(post("/api/permission-rules").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"read_file\",\"pattern\":\"x\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/permission-rules").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"shell\",\"pattern\":\"  \"}"))
            .andExpect(status().isBadRequest());

        verify(permissionRules, org.mockito.Mockito.never()).save(any());
    }

    /** 重复规则撞唯一约束 → 409（不裸 500） */
    @Test
    void createDuplicateReturns409() throws Exception {
        when(permissionRules.save(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        mockMvc.perform(post("/api/permission-rules").headers(authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"shell\",\"pattern\":\"pip install *\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("已存在")));
    }

    @Test
    void deleteScopesByCurrentIdentityAndReturns204() throws Exception {
        mockMvc.perform(delete("/api/permission-rules/7").headers(authHeaders))
            .andExpect(status().isNoContent());

        // (tenant, user, id) 三元收敛：非属主 id 不生效（删除幂等 204）
        verify(permissionRules).deleteByTenantIdAndUserIdAndId("default", "linmj", 7L);
    }
}
