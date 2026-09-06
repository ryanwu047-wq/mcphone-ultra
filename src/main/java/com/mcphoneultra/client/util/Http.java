package com.mcphoneultra.client.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** 极简 HTTP 客户端：带超时与浏览器 UA，供 Bilibili/YouTube/浏览器 App 用。 */
public final class Http {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

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
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 400) {
                return Optional.of(resp.body());
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 二進位下載（檔案管理 App 用）。回 null 表示失敗。 */
    public static byte[] getBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", UA)
                    .header("Accept", "*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 400) {
                return resp.body();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
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
