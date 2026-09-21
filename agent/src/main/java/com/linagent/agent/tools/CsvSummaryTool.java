package com.linagent.agent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * csv-analysis 技能的专属工具（groupedTools 绑定，read_skill 后才暴露）。
 */
public class CsvSummaryTool {

    private final Path workspaceRoot;

    public CsvSummaryTool(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Tool(description = "统计 CSV 文件：返回总行数（不含表头）、列数、表头字段列表")
    public String csv_summary(@ToolParam(description = "相对工作区根的 CSV 文件路径") String path) {
        Path p = new FileTools(workspaceRoot).resolveSafely(path);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        try {
            var lines = Files.readAllLines(p);
            if (lines.isEmpty() || lines.get(0).isBlank()) {
                return "空文件";
            }
            String[] header = lines.get(0).split(",");
            long dataRows = lines.size() - 1;
            return "总行数(不含表头) %d，列数 %d，表头: %s"
                .formatted(dataRows, header.length, String.join(", ", header));
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }

    public ToolCallback callback() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(this).build().getToolCallbacks();
        if (callbacks.length != 1) {
            throw new IllegalStateException("csv_summary 工具回调数量异常: " + callbacks.length);
        }
        return callbacks[0];
    }
}
