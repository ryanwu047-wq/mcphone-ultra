package com.mcphoneultra.client.util;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

/**
 * 压缩 / 解压助手（zip / 7z / rar）。
 *
 * - zip：支持写入与读取，支持 ZipCrypto / WinZip AES 密码。
 * - 7z：支持读取（含 AES 密码），不支持写入（7z 写入需原生库）。
 * - rar：仅 RAR4 读取，不支持加密（commons-compress 限制，会明确报错）。
 *   RAR 类在新版 commons-compress 里被挪走/移除，这里用反射调，类在就解、
 *   不在就明确报错，编译不依赖具体版本。
 * 所有操作纯 Java（Apache Commons Compress），无外部工具依赖。
 */
public final class ArchiveUtil {

    private ArchiveUtil() {}

    public static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar");
    }

    /** 解压到目标目录。password 可为 null。 */
    public static void extract(Path archive, Path dest, String password) throws IOException {
        Files.createDirectories(dest);
        String n = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip")) {
            extractZip(archive, dest, password);
        } else if (n.endsWith(".7z")) {
            extract7z(archive, dest, password);
        } else if (n.endsWith(".rar")) {
            extractRar(archive, dest, password);
        } else {
            throw new IOException("不支援的壓縮格式: " + n);
        }
    }

    /** 把 src（文件或目录）压成 zip 到 destZip。 */
    public static void createZip(Path src, Path destZip) throws IOException {
        Files.createDirectories(destZip.getParent());
        try (OutputStream fos = Files.newOutputStream(destZip);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            zos.setLevel(6);
            if (Files.isDirectory(src)) {
                try (var walk = Files.walk(src)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (Files.isDirectory(p)) continue;
                        String rel = src.relativize(p).toString().replace('\\', '/');
                        addZipEntry(zos, rel, p);
                    }
                }
            } else {
                addZipEntry(zos, src.getFileName().toString(), src);
            }
        }
    }

    private static void addZipEntry(ZipArchiveOutputStream zos, String name, Path p) throws IOException {
        ZipArchiveEntry e = new ZipArchiveEntry(name);
        zos.putArchiveEntry(e);
        try (InputStream in = Files.newInputStream(p)) {
            in.transferTo(zos);
        }
        zos.closeArchiveEntry();
    }

    private static void extractZip(Path archive, Path dest, String password) throws IOException {
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            if (password != null && !password.isEmpty()) {
                setZipPassword(zf, password);
            }
            var it = zf.getEntries();
            while (it.hasMoreElements()) {
                ZipArchiveEntry e = it.nextElement();
                Path out = dest.resolve(safe(e.getName())).normalize();
                if (!out.startsWith(dest)) continue;
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 设置 zip 密码。不同版本签名不同（char[] 或 byte[]），反射兼容。 */
    private static void setZipPassword(ZipFile zf, String password) throws IOException {
        try {
            zf.getClass().getMethod("setPassword", char[].class).invoke(zf, password.toCharArray());
        } catch (NoSuchMethodException e) {
            try {
                zf.getClass().getMethod("setPassword", byte[].class)
                        .invoke(zf, password.getBytes(StandardCharsets.UTF_8));
            } catch (ReflectiveOperationException e2) {
                throw new IOException("zip 密碼不支援（此版 commons-compress）");
            }
        } catch (ReflectiveOperationException e) {
            throw new IOException("zip 密碼設定失敗：" + e.getMessage());
        }
    }

    private static void extract7z(Path archive, Path dest, String password) throws IOException {
        try (SevenZFile f = password != null && !password.isEmpty()
                ? new SevenZFile(archive.toFile(), password.toCharArray())
                : new SevenZFile(archive.toFile())) {
            SevenZArchiveEntry e;
            while ((e = f.getNextEntry()) != null) {
                Path out = dest.resolve(safe(e.getName())).normalize();
                if (!out.startsWith(dest)) continue;
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = f.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    /** RAR4 解压（反射调用：新版 commons-compress 把 rar 拆走了）。 */
    private static void extractRar(Path archive, Path dest, String password) throws IOException {
        if (password != null && !password.isEmpty()) {
            throw new IOException("RAR 加密壓縮檔不支援（commons-compress 限制），請先在本機用 7-Zip 解開");
        }
        try {
            Class<?> risClass = Class.forName("org.apache.commons.compress.archivers.rar.RarArchiveInputStream");
            Class<?> entryClass = Class.forName("org.apache.commons.compress.archivers.rar.RarArchiveEntry");
            java.lang.reflect.Method getNext = risClass.getMethod("getNextEntry");
            java.lang.reflect.Method isDir = entryClass.getMethod("isDirectory");
            java.lang.reflect.Method getName = entryClass.getMethod("getName");
            try (InputStream fis = Files.newInputStream(archive);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                AutoCloseable ris = (AutoCloseable) risClass.getConstructor(InputStream.class).newInstance(bis);
                try {
                    Object e;
                    while ((e = getNext.invoke(ris)) != null) {
                        Path out = dest.resolve(safe((String) getName.invoke(e))).normalize();
                        if (!out.startsWith(dest)) continue;
                        if ((Boolean) isDir.invoke(e)) {
                            Files.createDirectories(out);
                            continue;
                        }
                        Files.createDirectories(out.getParent());
                        Files.copy((InputStream) ris, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    try {
                        ris.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("此版本無 RAR 支援，請改用 7-Zip 解開");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw new IOException("RAR 解壓失敗：" + (c == null ? e : c.getMessage()));
        } catch (ReflectiveOperationException e) {
            throw new IOException("RAR 解壓失敗：" + e.getMessage());
        }
    }

    /** 档内路径安全化：防 zip-slip。 */
    private static String safe(String name) {
        String s = name == null ? "" : name.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        return s;
    }

    /** 递归删除。 */
    public static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path f : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(f);
            }
        }
    }
}
