package com.linagent.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProjectPathResolver 行为契约：
 * 绝对路径原样返回；相对路径按 cwd → 父目录 上溯解析（mvn -pl web spring-boot:run
 * fork 出的 JVM cwd 是 web 模块目录，仓库根的 skills/workspace 需上溯一级）；
 * 均未命中时回退 cwd 解析（保持"目录不存在告警"的旧行为，交由消费方容错）。
 */
class ProjectPathResolverTest {

    @Test
    void absolutePathReturnedAsIs(@TempDir Path tmp) {
        assertThat(ProjectPathResolver.resolveDir(tmp.toString(), tmp)).isEqualTo(tmp);
    }

    @Test
    void relativeDirUnderCurrentDirWins(@TempDir Path tmp) throws IOException {
        Files.createDirectory(tmp.resolve("skills"));
        assertThat(ProjectPathResolver.resolveDir("skills", tmp)).isEqualTo(tmp.resolve("skills"));
    }

    @Test
    void relativeDirFoundInParentWhenMissingInCurrentDir(@TempDir Path tmp) throws IOException {
        // 模拟 mvn -pl web spring-boot:run：cwd 是 web 子目录，skills 在仓库根
        Path webModule = Files.createDirectory(tmp.resolve("web"));
        Files.createDirectory(tmp.resolve("skills"));
        assertThat(ProjectPathResolver.resolveDir("skills", webModule))
            .isEqualTo(tmp.resolve("skills"));
    }

    @Test
    void fallsBackToCurrentDirResolutionWhenNothingMatches(@TempDir Path tmp) {
        assertThat(ProjectPathResolver.resolveDir("skills", tmp)).isEqualTo(tmp.resolve("skills"));
    }

    @Test
    void normalizesDotSlashPrefix(@TempDir Path tmp) throws IOException {
        Files.createDirectory(tmp.resolve("skills"));
        assertThat(ProjectPathResolver.resolveDir("./skills", tmp)).isEqualTo(tmp.resolve("skills"));
    }
}
