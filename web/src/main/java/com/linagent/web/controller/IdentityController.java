package com.linagent.web.controller;

import com.linagent.agent.persistence.po.AppUser;
import com.linagent.agent.persistence.repository.AppUserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 身份候选名单（前端切换器数据源）。免鉴权：AuthContextFilter 豁免本路径——
 * 切换器需要在任何身份生效前拿到候选。本地工具取舍（无密码体系下名单非秘密），
 * 登录体系上线时收紧为需登录。
 */
@RestController
public class IdentityController {

    /** 免鉴权路径唯一来源：AuthContextFilter 的豁免引用此常量，改路径只动这里 */
    public static final String PATH = "/api/identity/options";

    private final AppUserRepository users;

    public IdentityController(AppUserRepository users) {
        this.users = users;
    }

    public record UserOption(String userId, String name) {
    }

    public record TenantOptions(String tenantId, List<UserOption> users) {
    }

    @GetMapping(PATH)
    public List<TenantOptions> options() {
        Map<String, List<UserOption>> grouped = new LinkedHashMap<>();
        for (AppUser u : users.findAll()) {
            grouped.computeIfAbsent(u.tenantId(), t -> new ArrayList<>())
                .add(new UserOption(u.userId(), u.name()));
        }
        return grouped.entrySet().stream()
            .map(e -> new TenantOptions(e.getKey(), e.getValue()))
            .toList();
    }
}
