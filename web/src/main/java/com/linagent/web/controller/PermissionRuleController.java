package com.linagent.web.controller;

import com.linagent.agent.context.AuthContext;
import com.linagent.agent.context.AuthContextHolder;
import com.linagent.agent.persistence.po.PermissionRule;
import com.linagent.agent.persistence.repository.PermissionRuleRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户审批规则 CRUD（Task 6，spec §8 设置页 /api/permission-rules）。
 * 当前身份（AuthContextHolder）过滤——规则按 (tenant_id, user_id) 隔离，与 conversation 同粒度。
 * POST 校验 tool ∈ {shell, write_file}（规则引擎仅消费这两个工具的规则）与 pattern 非空；
 * 重复规则撞唯一约束 → 409（幂等提示，不裸 500）。
 */
@RestController
public class PermissionRuleController {

    private final PermissionRuleRepository permissionRules;

    public PermissionRuleController(PermissionRuleRepository permissionRules) {
        this.permissionRules = permissionRules;
    }

    public record CreateRuleRequest(
            @NotBlank @Pattern(regexp = "shell|write_file") String toolName,
            @NotBlank String pattern) {
    }

    public record RuleResponse(Long id, String toolName, String pattern, String effect, String createdAt) {

        static RuleResponse from(PermissionRule rule) {
            return new RuleResponse(rule.id(), rule.toolName(), rule.pattern(),
                rule.effect(), rule.createdAt() == null ? null : rule.createdAt().toString());
        }
    }

    @GetMapping("/api/permission-rules")
    public List<RuleResponse> list() {
        AuthContext ctx = AuthContextHolder.require();
        return permissionRules.findByTenantIdAndUserIdAndEffect(ctx.tenantId(), ctx.userId(), "ALLOW")
            .stream().map(RuleResponse::from).toList();
    }

    @PostMapping("/api/permission-rules")
    public ResponseEntity<?> create(@Valid @RequestBody CreateRuleRequest request) {
        AuthContext ctx = AuthContextHolder.require();
        try {
            PermissionRule saved = permissionRules.save(
                PermissionRule.allow(ctx.tenantId(), ctx.userId(), request.toolName(), request.pattern()));
            return ResponseEntity.ok(RuleResponse.from(saved));
        }
        catch (DataIntegrityViolationException e) {
            // 唯一约束 (tenant_id, user_id, tool_name, pattern)：重复创建幂等挡回
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "规则已存在: " + request.toolName() + " / " + request.pattern()));
        }
    }

    /** (tenant, user, id) 三元收敛删除：非属主 id 不生效（幂等，统一 204） */
    @DeleteMapping("/api/permission-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        AuthContext ctx = AuthContextHolder.require();
        permissionRules.deleteByTenantIdAndUserIdAndId(ctx.tenantId(), ctx.userId(), id);
    }
}
