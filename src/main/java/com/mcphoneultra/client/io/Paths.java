package com.mcphoneultra.client.io;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 本扩展所有数据的根目录：游戏目录的 config/mcphone_ultra/ */
public final class Paths {

    private Paths() {}

    public static Path base() {
        return FMLPaths.CONFIGDIR.get().resolve("mcphone").resolve("ultra");
    }

    /** 取一个子目录（自动创建）。形如 dir("files")、dir("music") */
    public static Path dir(String... parts) {
        Path p = base();
        for (String part : parts) p = p.resolve(part);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException("無法建立目錄 " + p, e);
        }
        return p;
    }

    public static Path file(String... parts) {
        Path p = base();
        for (int i = 0; i < parts.length - 1; i++) p = p.resolve(parts[i]);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException("無法建立目錄 " + p, e);
        }
        return p.resolve(parts[parts.length - 1]);
    }

    /** 文件名安全化：去掉路径分隔符与非法字符，并防目录穿越（.. 序列）。 */
    public static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\[\\]]", "_").trim();
        // 防目录穿越：连续点号（".."、"...") 全部平掉；开头的单点也换成下划线
        s = s.replaceAll("\\.\\.+", "_");
        s = s.replaceAll("^\\.", "_");
        if (s.isEmpty()) s = "untitled";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
