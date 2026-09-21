package com.linagent.agent.workspace;

import com.linagent.agent.tools.FileTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceResolverTest {

    @TempDir
    Path tmp;

    private WorkspaceResolver resolver() {
        return new WorkspaceResolver(tmp.toString()); // 绝对路径直通（ProjectPathResolver 语义）
    }

    @Test
    void provisionsPersonalRootWithSharedSymlink() throws IOException {
        Path personal = resolver().personalRoot("tenant-a", "linmj");
        assertThat(personal).isEqualTo(tmp.resolve("tenant-a").resolve("users").resolve("linmj"));
        assertThat(Files.isDirectory(personal)).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("tenant-a").resolve("shared"))).isTrue();
        assertThat(Files.isSymbolicLink(personal.resolve("shared"))).isTrue();
        assertThat(Files.readSymbolicLink(personal.resolve("shared")))
            .isEqualTo(Path.of("..", "..", "shared"));
    }

    @Test
    void provisioningIsIdempotent() {
        WorkspaceResolver r = resolver();
        Path first = r.personalRoot("tenant-a", "linmj");
        Path second = r.personalRoot("tenant-a", "linmj");
        assertThat(second).isEqualTo(first); // 二次不抛、同路径
    }

    @Test
    void sharedVisibleFromPersonalRootButSiblingsSealed() throws IOException {
        WorkspaceResolver r = resolver();
        Path personal = r.personalRoot("tenant-a", "linmj");
        r.personalRoot("tenant-a", "zhangsan"); // 兄弟用户
        Files.writeString(tmp.resolve("tenant-a").resolve("shared").resolve("规范.md"), "团队规范");

        FileTools tools = new FileTools(personal);
        assertThat(tools.read_file("shared/规范.md")).isEqualTo("团队规范"); // shared 可达
        assertThatThrownBy(() -> tools.read_file("../zhangsan/x")) // 兄弟越界
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void illegalIdentitySegmentRejected() {
        WorkspaceResolver r = resolver();
        assertThatThrownBy(() -> r.personalRoot("../etc", "linmj"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.personalRoot("tenant-a", "a/b"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
