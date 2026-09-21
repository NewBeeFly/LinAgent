package com.linagent.agent.workspace;

import com.linagent.agent.config.ProjectPathResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 租户/个人两级工作区解析（spec §6）：
 * {基根}/{tenant}/shared 为租户共享真实目录；{基根}/{tenant}/users/{user} 为个人根（工具沙箱），
 * 根内 shared 符号链接（相对路径 ../../shared，基根整体搬迁不断链）挂载租户共享区。
 * FileTools.resolveSafely 的 normalize 纯词法不解析链接：链接在根内通过校验、
 * ../兄弟目录 越界拒绝——隔离由此天然成立。
 * 幂等：目录/链接存在即跳过。身份段白名单校验防路径穿越（纵深防御，虽经 app_user 校验）。
 */
@Component
public class WorkspaceResolver {

    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Path baseRoot;

    public WorkspaceResolver(@Value("${agent.workspace-root:./workspace}") String workspaceRoot) {
        this.baseRoot = ProjectPathResolver.resolveDir(workspaceRoot);
    }

    public Path baseRoot() {
        return baseRoot;
    }

    public Path sharedRoot(String tenantId) {
        return baseRoot.resolve(requireSegment(tenantId)).resolve("shared");
    }

    /** 个人根（含幂等 provision：shared 目录、个人目录、shared 相对符号链接） */
    public Path personalRoot(String tenantId, String userId) {
        Path tenantDir = baseRoot.resolve(requireSegment(tenantId));
        Path shared = tenantDir.resolve("shared");
        Path personal = tenantDir.resolve("users").resolve(requireSegment(userId));
        try {
            Files.createDirectories(shared);
            Files.createDirectories(personal);
            Path link = personal.resolve("shared");
            if (!Files.exists(link)) {
                Files.createSymbolicLink(link, Path.of("..", "..", "shared"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("工作区初始化失败: " + personal, e);
        }
        return personal;
    }

    private String requireSegment(String value) {
        if (value == null || !SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("非法身份段（仅字母数字_-，1-64 字符）: " + value);
        }
        return value;
    }
}
