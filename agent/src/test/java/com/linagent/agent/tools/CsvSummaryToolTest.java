package com.linagent.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvSummaryToolTest {

    @TempDir
    Path tempDir;
    private CsvSummaryTool tool;

    @BeforeEach
    void setUp() {
        tool = new CsvSummaryTool(tempDir);
    }

    @Test
    void summarizesCsv() throws Exception {
        Files.writeString(tempDir.resolve("data.csv"), "name,age\nAlice,30\nBob,25\n");
        String result = tool.csv_summary("data.csv");
        assertThat(result).contains("2", "2", "name, age");
    }

    @Test
    void emptyFileReportsBlank() throws Exception {
        Files.writeString(tempDir.resolve("empty.csv"), "\n");
        assertThat(tool.csv_summary("empty.csv")).isEqualTo("空文件");
    }

    @Test
    void missingFileIsRejected() {
        assertThatThrownBy(() -> tool.csv_summary("nope.csv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("文件不存在");
    }

    @Test
    void pathEscapeIsRejected() {
        assertThatThrownBy(() -> tool.csv_summary("../outside.csv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("路径越界");
    }

    @Test
    void callbackNameIsCsvSummary() {
        assertThat(tool.callback().getToolDefinition().name()).isEqualTo("csv_summary");
    }
}
