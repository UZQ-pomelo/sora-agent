package com.sora.sora_agent.security;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import java.io.IOException;

/**
 * 路径安全工具：将不可信的文件名安全解析到指定沙箱目录内，防止路径穿越。
 *
 * <p>防护点：空名、NUL 字符、绝对路径/盘符/前导斜杠（Windows 下 {@code /foo}
 * 会被当作当前盘根路径）、{@code ..} 归一化后越出沙箱根目录。</p>
 */
public final class PathSafety {

    private PathSafety() {
    }

    /**
     * 在 baseDir 内安全解析文件名。
     *
     * @param baseDir  沙箱根目录（绝对或相对均可，内部会归一化）
     * @param fileName 不可信的文件名
     * @return 安全路径（必在 baseDir 内）
     * @throws IllegalArgumentException 文件名非法或解析后越出沙箱
     */
    public static Path resolve(String baseDir, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (fileName.indexOf(Character.MIN_VALUE) >= 0) {
            throw new IllegalArgumentException("文件名包含非法字符");
        }
        Path fileNamePath;
        try {
            fileNamePath = Path.of(fileName);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("非法文件名: " + fileName, e);
        }
        // 拒绝绝对路径 / 盘符 / 根路径（Windows 下前导斜杠会被当作当前盘根路径）
        if (fileNamePath.isAbsolute() || fileNamePath.getRoot() != null) {
            throw new IllegalArgumentException("文件名不能是绝对路径或盘符: " + fileName);
        }
        if (fileName.matches("^[a-zA-Z]:.*")) {
            throw new IllegalArgumentException("文件名不能包含盘符: " + fileName);
        }
        // 拒绝含冒号的文件名（Windows NTFS ADS 如 file.txt:stream，可绕出沙箱写流）
        if (fileName.indexOf(':') >= 0) {
            throw new IllegalArgumentException("文件名不能包含冒号: " + fileName);
        }
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(fileNamePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("路径越界: " + fileName);
        }
        // 已存在的文件做 realpath 校验，防符号链接指向沙箱外
        try {
            if (Files.exists(resolved)) {
                Path real = resolved.toRealPath();
                if (!real.startsWith(base)) {
                    throw new IllegalArgumentException("路径越界(符号链接): " + fileName);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("路径校验失败: " + fileName, e);
        }
        return resolved;
    }
}
