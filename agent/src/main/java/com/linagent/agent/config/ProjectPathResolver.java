package com.linagent.agent.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 项目内相对目录解析。mvn -pl web spring-boot:run fork 出的 JVM 工作目录是 web 模块目录，
 * "skills"/"workspace" 等仓库根目录需按 cwd → 父目录 上溯一级定位
 * （与 SkillsSmokeTest.projectSkillsRoot 的上溯思路一致；只上溯一级，避免命中无关的外层同名目录）。
 * 绝对路径原样返回；均未命中时回退 cwd 解析，保持"目录不存在告警"的旧容错行为。
 */
public final class ProjectPathResolver {

    private ProjectPathResolver() {
    }

    public static Path resolveDir(String configured) {
        return resolveDir(configured, Path.of("").toAbsolutePath());
    }

    /** 包内可见，便于以显式 base 目录做单测 */
    static Path resolveDir(String configured, Path currentDir) {
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path;
        }
        Path candidate = currentDir.resolve(configured).normalize();
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path parent = currentDir.getParent();
        if (parent != null) {
            Path fromParent = parent.resolve(configured).normalize();
            if (Files.isDirectory(fromParent)) {
                return fromParent;
            }
        }
        return candidate;
    }
}
