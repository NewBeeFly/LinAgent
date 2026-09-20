package com.javaagent.agent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区内文件读写工具。所有路径必须解析到工作区根之内，防目录穿越。
 */
public class FileTools {

    private final Path workspaceRoot;

    public FileTools(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Tool(description = "读取工作区内文本文件内容。path 为相对工作区根目录的路径")
    public String read_file(@ToolParam(description = "相对工作区根的文件路径") String path) {
        Path p = resolveSafely(path);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("文件不存在: " + path);
        }
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path + " - " + e.getMessage(), e);
        }
    }

    @Tool(description = "写入文本文件（UTF-8，覆盖已有内容），自动创建父目录。path 为相对工作区根目录的路径")
    public String write_file(@ToolParam(description = "相对工作区根的文件路径") String path,
                             @ToolParam(description = "要写入的完整文本内容") String content) {
        Path p = resolveSafely(path);
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content);
            return "已写入 " + workspaceRoot.relativize(p) + " (" + content.length() + " 字符)";
        } catch (IOException e) {
            throw new IllegalStateException("写入失败: " + path + " - " + e.getMessage(), e);
        }
    }

    @Tool(description = "列出目录下的文件与子目录。path 为相对工作区根目录的路径，\".\" 表示根")
    public List<String> list_dir(@ToolParam(description = "相对工作区根的目录路径") String path) {
        Path p = resolveSafely(path);
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("目录不存在: " + path);
        }
        try (var stream = Files.list(p)) {
            List<String> names = new ArrayList<>();
            stream.sorted().forEach(entry ->
                names.add(entry.getFileName().toString() + (Files.isDirectory(entry) ? "/" : "")));
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("列目录失败: " + path + " - " + e.getMessage(), e);
        }
    }

    Path resolveSafely(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        Path p = workspaceRoot.resolve(relative).normalize();
        if (!p.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("路径越界: " + relative);
        }
        return p;
    }

    public ToolCallback[] toCallbacks() {
        return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks();
    }
}
