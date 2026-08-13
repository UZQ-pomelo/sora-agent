package com.sora.sora_agent.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandGuard} 纯单元测试。
 */
class CommandGuardTest {

    @Test
    void emptyAllowlistAllowsAll() {
        CommandGuard guard = new CommandGuard(List.of());
        assertTrue(guard.isAllowed("anything --dangerous"));
    }

    @Test
    void nullAllowlistAllowsAll() {
        CommandGuard guard = new CommandGuard(null);
        assertTrue(guard.isAllowed("dir"));
    }

    @Test
    void exactPrefixAndSpaceBoundaryAllowed() {
        CommandGuard guard = new CommandGuard(List.of("git"));
        assertTrue(guard.isAllowed("git"));
        assertTrue(guard.isAllowed("git log --oneline"));
    }

    @Test
    void wordBoundaryEnforced() {
        CommandGuard guard = new CommandGuard(List.of("git"));
        assertFalse(guard.isAllowed("gitty config")); // gitty ≠ git 前缀
    }

    @Test
    void otherCommandRejected() {
        CommandGuard guard = new CommandGuard(List.of("git", "npm"));
        assertTrue(guard.isAllowed("npm run build"));
        assertFalse(guard.isAllowed("rm -rf /"));
    }

    @Test
    void cmdMetacharBypassRejected() {
        // cmd 元字符可拼接额外命令，白名单模式下必须拒绝
        CommandGuard guard = new CommandGuard(List.of("git"));
        assertFalse(guard.isAllowed("git & powershell -c whoami"));
        assertFalse(guard.isAllowed("dir | findstr x"));
        assertFalse(guard.isAllowed("echo a > out.txt"));
        assertFalse(guard.isAllowed("echo a < in.txt"));
    }

    @Test
    void blankCommandRejectedWhenConfigured() {
        CommandGuard guard = new CommandGuard(List.of("git"));
        assertFalse(guard.isAllowed(null));
        assertFalse(guard.isAllowed("   "));
    }
}
