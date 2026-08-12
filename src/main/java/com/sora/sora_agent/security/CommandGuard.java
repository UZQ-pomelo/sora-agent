package com.sora.sora_agent.security;

import java.util.List;

/**
 * 终端命令守卫：可选的命令前缀白名单。
 *
 * <p>白名单为空 = 放行（显式开启终端工具者的责任）。非空时，命令必须等于
 * 某个前缀，或以「前缀 + 空格」开头。</p>
 *
 * <p><b>说明</b>：前缀白名单属于附加加固而非可靠防线（例如 {@code git} 放行后
 * 仍可执行 {@code git clone https://evil} 等变体）。真正的防线是"默认关闭 +
 * 认证 + 资源守卫"。</p>
 */
public final class CommandGuard {

    private final List<String> allowPrefixes;

    public CommandGuard(List<String> allowPrefixes) {
        this.allowPrefixes = (allowPrefixes == null || allowPrefixes.isEmpty())
                ? List.of()
                : List.copyOf(allowPrefixes);
    }

    /**
     * @return 命令是否被允许执行
     */
    public boolean isAllowed(String command) {
        if (allowPrefixes.isEmpty()) {
            return true;
        }
        if (command == null || command.isBlank()) {
            return false;
        }
        String cmd = command.trim();
        String lower = cmd.toLowerCase();
        for (String prefix : allowPrefixes) {
            String p = prefix.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (cmd.equalsIgnoreCase(p) || lower.startsWith(p.toLowerCase() + " ")) {
                return true;
            }
        }
        return false;
    }
}
