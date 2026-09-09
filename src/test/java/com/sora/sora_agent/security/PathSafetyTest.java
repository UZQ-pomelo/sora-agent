package com.sora.sora_agent.security;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PathSafety} 纯单元测试 — 不依赖 Spring 上下文，离线可跑。
 */
class PathSafetyTest {

    private static final String BASE = "C:/tmp/sandbox";

    private static boolean inside(String base, Path p) {
        return p.toAbsolutePath().normalize().startsWith(Path.of(base).toAbsolutePath().normalize());
    }

    @Test
    void normalFileNameResolvesInside() {
        assertTrue(inside(BASE, PathSafety.resolve(BASE, "report.txt")));
    }

    @Test
    void nestedSubdirectoryAllowed() {
        assertTrue(inside(BASE, PathSafety.resolve(BASE, "a/b/c.txt")));
    }

    @Test
    void parentTraversalRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "../evil.txt"));
    }

    @Test
    void deepParentTraversalRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "../../../../etc/passwd"));
    }

    @Test
    void mixedTraversalRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "sub/../../evil.txt"));
    }

    @Test
    void backslashTraversalRejectedOnWindows() {
        assumeTrue(File.separatorChar == '\\', "反斜杠穿越仅在 Windows 路径语义下生效");
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "..\\..\\evil.txt"));
    }

    @Test
    void absoluteWindowsPathRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "C:\\windows\\system32"));
    }

    @Test
    void driveLetterRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "D:/x"));
    }

    @Test
    void leadingSlashRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "/etc/passwd"));
    }

    @Test
    void blankOrNullRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, "   "));
        assertThrows(IllegalArgumentException.class, () -> PathSafety.resolve(BASE, null));
    }

    @Test
    void chineseFileNameAllowed() {
        Path p = PathSafety.resolve(BASE, "测试报告.pdf");
        assertTrue(p.toAbsolutePath().normalize().getFileName().toString().contains("测试报告"));
    }
}
