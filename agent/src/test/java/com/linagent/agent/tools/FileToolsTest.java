package com.linagent.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolsTest {

    @TempDir
    Path tempDir;
    private FileTools fileTools;

    @BeforeEach
    void setUp() {
        fileTools = new FileTools(tempDir);
    }

    @Test
    void readFileReturnsContent() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        assertThat(fileTools.readFile("a.txt")).isEqualTo("hello");
    }

    @Test
    void writeFileCreatesFileAndParentDirs() {
        fileTools.writeFile("sub/b.txt", "内容");
        assertThat(tempDir.resolve("sub/b.txt")).hasContent("内容");
    }

    @Test
    void listDirReturnsEntryNames() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "x");
        Files.createDirectory(tempDir.resolve("d"));
        assertThat(fileTools.listDir(".")).contains("a.txt", "d/");
    }

    @Test
    void pathEscapeIsRejected() {
        assertThatThrownBy(() -> fileTools.readFile("../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("路径越界");
    }

    @Test
    void absolutePathOutsideWorkspaceIsRejected() {
        assertThatThrownBy(() -> fileTools.readFile("/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingFileIsRejected() {
        assertThatThrownBy(() -> fileTools.readFile("nope.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    void toCallbacksExposesThreeTools() {
        List<String> names = java.util.Arrays.stream(fileTools.toCallbacks())
            .map(c -> c.getToolDefinition().name()).toList();
        assertThat(names).containsExactlyInAnyOrder("read_file", "write_file", "list_dir");
    }
}
