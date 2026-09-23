package com.linagent.agent.approval;

import com.linagent.agent.persistence.po.PermissionRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 命令级审批规则引擎（spec §5 匹配语义，纯逻辑无 IO）。
 *
 * <p>构造即绑定本次判定的规则输入：userRules 由调用方按 (tenant, user) 查库后传入
 * （每轮构建，引擎不做 IO）；session 作用域规则经 {@link SessionRules} 只读接口按
 * {@link ApprovalContext} 定位。evaluate 可重入（无共享可变状态）。
 *
 * <p>评估序固定：内置白名单（仅 shell）→ session → user → 审批，命中即放行，
 * 顺序经 {@link SubVerdict#source()} 可见；二期 deny 层插最前即天然 deny-first。
 */
public class PermissionRuleEngine {

    public static final String SOURCE_BUILTIN = "BUILTIN";
    public static final String SOURCE_SESSION = "session";
    public static final String SOURCE_USER = "user";

    /** shell 内置只读命令白名单（spec §5，硬编码不可配置；前缀段匹配，带参数视为同一命令） */
    public static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "ls", "cat", "pwd", "head", "tail", "grep", "find", "wc", "which", "diff",
            "stat", "du", "echo", "cd",
            "git status", "git log", "git diff", "git show",
            "python --version", "python3 --version");

    /** read 工具恒放行（越界校验由工具层承担，spec §5 默认策略） */
    private static final Set<String> ALWAYS_ALLOWED_TOOLS =
            Set.of("read_file", "list_dir", "csv_summary", "read_skill");

    private static final String SHELL = "shell";
    private static final String WRITE_FILE = "write_file";

    /** 复合命令分隔符：&& / || / ; / |（spec §5 固定清单，不含重定向与命令替换） */
    private static final Pattern SEPARATOR = Pattern.compile("&&|\\|\\||;|\\|");

    private final List<PermissionRule> userRules;
    private final SessionRules sessionRules;

    public PermissionRuleEngine(List<PermissionRule> userRules, SessionRules sessionRules) {
        // 判空政策：userRules 是跨任务契约入参（Task 4 传仓库查询结果），null 视为无规则
        this.userRules = userRules == null ? List.of() : List.copyOf(userRules);
        this.sessionRules = sessionRules;
    }

    /** 判定一次工具调用：needsApproval=true 时 items 携带唯一待审批项（含各段明细与建议规则） */
    public Verdict evaluate(String toolName, String callId, String payload, ApprovalContext ctx) {
        if (ALWAYS_ALLOWED_TOOLS.contains(toolName)) {
            return new Verdict(false, List.of());
        }
        if (payload == null || payload.isBlank()) {
            // shell 空命令无可拆段（no-op）放行；write_file 空路径无法核对目标 → 审批
            return WRITE_FILE.equals(toolName)
                    ? pendingVerdict(toolName, callId, payload, List.of(new SubVerdict("", false, null)))
                    : new Verdict(false, List.of());
        }

        Set<String> sessionPatterns =
                sessionRules.patterns(ctx.tenantId(), ctx.userId(), ctx.conversationId(), toolName);
        List<String> userPatterns = userRules.stream()
                .filter(rule -> toolName.equals(rule.toolName()))
                .filter(rule -> "ALLOW".equals(rule.effect()))
                .map(PermissionRule::pattern)
                .toList();

        List<SubVerdict> subVerdicts;
        if (WRITE_FILE.equals(toolName)) {
            subVerdicts = List.of(judge(toolName, payload, sessionPatterns, userPatterns));
        } else {
            subVerdicts = splitSegments(payload).stream()
                    .map(segment -> judge(toolName, segment, sessionPatterns, userPatterns))
                    .toList();
            if (subVerdicts.isEmpty()) {
                return new Verdict(false, List.of());
            }
        }
        return subVerdicts.stream().anyMatch(sv -> !sv.allowed())
                ? pendingVerdict(toolName, callId, payload, subVerdicts)
                : new Verdict(false, List.of());
    }

    /** 单段判定：白名单（仅 shell）→ session → user，命中即返回，未命中 allowed=false/source=null */
    private SubVerdict judge(String toolName, String segment, Set<String> sessionPatterns, List<String> userPatterns) {
        if (SHELL.equals(toolName)
                && READ_ONLY_COMMANDS.stream().anyMatch(cmd -> commandPrefixMatches(cmd, segment))) {
            return new SubVerdict(segment, true, SOURCE_BUILTIN);
        }
        if (sessionPatterns.stream().anyMatch(pattern -> matches(pattern, segment, toolName))) {
            return new SubVerdict(segment, true, SOURCE_SESSION);
        }
        if (userPatterns.stream().anyMatch(pattern -> matches(pattern, segment, toolName))) {
            return new SubVerdict(segment, true, SOURCE_USER);
        }
        return new SubVerdict(segment, false, null);
    }

    /** pattern 匹配：'*' 工具级；write_file 路径前缀（'/*' 尾缀，边界 '/'）；其余命令段前缀（' *' 尾缀，边界空白/结尾） */
    static boolean matches(String pattern, String value, String toolName) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (WRITE_FILE.equals(toolName)) {
            if (pattern.endsWith("/*")) {
                String dir = pattern.substring(0, pattern.length() - 2);
                return value.equals(dir) || value.startsWith(dir + "/");
            }
            return value.equals(pattern);
        }
        if (pattern.endsWith(" *")) {
            return commandPrefixMatches(pattern.substring(0, pattern.length() - 2), value);
        }
        return value.equals(pattern);
    }

    /** 前缀后必须是结尾或空白（"pip install *" 命中 "pip install pandas"，不命中 "pip installx"） */
    private static boolean commandPrefixMatches(String prefix, String command) {
        return command.equals(prefix)
                || (command.startsWith(prefix)
                    && Character.isWhitespace(command.charAt(prefix.length())));
    }

    /** 按 && / || / ; / | 拆子命令并去空白段（空段不参与判定，真实段照常） */
    private static List<String> splitSegments(String payload) {
        List<String> segments = new ArrayList<>();
        for (String part : SEPARATOR.split(payload)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }
        return segments;
    }

    private static Verdict pendingVerdict(String toolName, String callId, String payload,
                                          List<SubVerdict> subVerdicts) {
        // arguments 与 payload 同值：引擎只接收提取后的命令/路径，原始 arguments JSON 由持有方（Task 4）覆写
        PendingItem item = new PendingItem(callId, toolName, payload, payload,
                subVerdicts, suggestPattern(toolName, payload));
        return new Verdict(true, List.of(item));
    }

    /** 建议规则：命令取前 2 个非选项 token + " *"；不足 2 个 → 工具级 '*'；write_file 取父目录 + "/*"（根级文件无父目录 → '*'） */
    public static String suggestPattern(String toolName, String payload) {
        if (payload == null || payload.isBlank()) {
            return "*";
        }
        if (WRITE_FILE.equals(toolName)) {
            int slash = payload.lastIndexOf('/');
            return slash <= 0 ? "*" : payload.substring(0, slash) + "/*";
        }
        List<String> tokens = new ArrayList<>();
        for (String token : payload.trim().split("\\s+")) {
            if (token.startsWith("-")) {
                continue;
            }
            tokens.add(token);
            if (tokens.size() == 2) {
                break;
            }
        }
        return tokens.size() < 2 ? "*" : String.join(" ", tokens) + " *";
    }

    // —— 产出类型（Task 4/5/6 消费，签名与 task brief 逐字一致）——

    /** 一次工具调用的判定结果：needsApproval=true 时 items 携带待审批项，放行路径 items 为空 */
    public record Verdict(boolean needsApproval, List<PendingItem> items) {
    }

    /**
     * 待审批项。arguments 为工具原始参数——引擎侧与 payload 同值（引擎只见提取后的命令/路径），
     * 持有原始 arguments JSON 的调用方可覆写；subVerdicts 为各子命令判定明细；
     * suggestedRule 为「批准并记住」将生成的规则预览（可编辑）。
     */
    public record PendingItem(String callId, String toolName, String arguments, String payload,
                              List<SubVerdict> subVerdicts, String suggestedRule) {
    }

    /** 单个子命令判定：source ∈ BUILTIN / session / user（评估序可见）；未命中时 allowed=false、source=null */
    public record SubVerdict(String segment, boolean allowed, String source) {
    }

    /** 会话规则定位 key（session 作用域规则按 tenant + user + conversation 隔离） */
    public record ApprovalContext(String tenantId, String userId, Long conversationId) {
    }
}
