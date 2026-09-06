package com.mcphoneultra.client.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** 极简 HTTP 客户端：带超时、浏览器 UA 与【响应大小上限】。 */
public final class Http {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 二進位下載（檔案管理 App）上限：50 MB，防惡意連結直接 OOM */
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    /** 文字/JSON（Bilibili/YouTube/瀏覽器 App）上限：8 MB */
    private static final long MAX_TEXT = 8L * 1024 * 1024;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}

    public static Optional<String> get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 400) {
                return Optional.empty();
            }
            if (!withinLimit(resp, MAX_TEXT)) {
                return Optional.empty();
            }
            try (InputStream in = resp.body()) {
                byte[] all = readLimited(in, MAX_TEXT);
                if (all == null) return Optional.empty();
                return Optional.of(new String(all, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 二進位下載（檔案管理 App 用）。回 null 表示失敗或超過 50MB 上限。 */
    public static byte[] getBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", UA)
                    .header("Accept", "*/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 400) {
                return null;
            }
            if (!withinLimit(resp, MAX_BYTES)) {
                return null;
            }
            try (InputStream in = resp.body()) {
                return readLimited(in, MAX_BYTES);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Content-Length 已知且超限 → 直接拒絕，不讀取 */
    private static boolean withinLimit(HttpResponse<InputStream> resp, long max) {
        long len = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        return len <= 0 || len <= max;
    }

    /** 流式讀取，超過 max 立即中斷並回 null（防無限串流） */
    private static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 在系统浏览器打开链接（桌面环境）。 */
    public static boolean openExternal(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", url).start();
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }
}
