package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 螢幕錄製：把遊戲畫面按 10fps 截成 PNG 幀存到 files/recordings/，
 * 停止後若系統有 ffmpeg 就自動合成 mp4（沒有就保留幀，可自己合成）。
 */
public final class ScreenRecorderApp extends BaseApp {

    public ScreenRecorderApp() {
        super("screenrec", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new RecPage();
    }

    private static final class RecPage extends ClickablePage {
        private static final long INTERVAL_MS = 100;   // 10fps
        private boolean recording;
        private String session = "";
        private long lastSnap;
        private int frameCount;
        private final List<String> videos = new ArrayList<>();
        private final Scroller scroller = new Scroller();
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void reload() {
            videos.clear();
            Path dir = Paths.dir("recordings");
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))
                        .map(p -> p.getFileName().toString())
                        .forEach(videos::add);
            } catch (IOException ignored) {
            }
            videos.sort(Comparator.reverseOrder());
        }

        private void start() {
            session = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            frameCount = 0;
            lastSnap = 0;
            recording = true;
            toast("開始錄製 → " + session);
        }

        private void stop() {
            recording = false;
            toast("已錄 " + frameCount + " 幀，合成 mp4…");
            int frames = frameCount;
            String sess = session;
            Thread t = new Thread(() -> {
                Path dir = Paths.dir("recordings");
                if (frames == 0) {
                    toastLater("沒有錄到幀");
                    return;
                }
                String ffmpeg = findFfmpeg();
                if (ffmpeg == null) {
                    toastLater("沒找到 ffmpeg，幀保留在 recordings/" + sess + "/");
                    return;
                }
                try {
                    Process p = new ProcessBuilder(ffmpeg, "-y",
                            "-framerate", "10",
                            "-i", com.mcphoneultra.client.util.Exec.norm(dir.resolve(sess).resolve("frame_%05d.png")),
                            "-c:v", "libx264", "-pix_fmt", "yuv420p",
                            com.mcphoneultra.client.util.Exec.norm(dir.resolve(sess + ".mp4")))
                            .redirectErrorStream(true)
                            .start();
                    // 讀掉輸出：ffmpeg 進度會寫管道，沒人讀會塞滿緩衝導致 waitFor 永不返回
                    Thread drain = new Thread(() -> {
                        try {
                            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                        } catch (IOException ignored) {
                        }
                    }, "mcphone-ultra-ffmpeg-drain");
                    drain.setDaemon(true);
                    drain.start();
                    if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        toastLater("ffmpeg 合成逾時");
                        return;
                    }
                    toastLater("已合成 " + sess + ".mp4");
                    Minecraft.getInstance().execute(() -> reload());
                } catch (Exception e) {
                    toastLater("ffmpeg 合成失敗");
                }
            }, "mcphone-ultra-ffmpeg");
            t.setDaemon(true);
            t.start();
        }

        private void toastLater(String s) {
            Minecraft.getInstance().execute(() -> toast(s));
        }

        private static String findFfmpeg() {
            String[] candidates = { "ffmpeg", "ffmpeg.exe" };
            for (String c : candidates) {
                try {
                    Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                    p.waitFor();
                    if (p.exitValue() == 0) return c;
                } catch (Exception ignored) {
                }
            }
            return null;
        }

        /** 在渲染執行緒上每幀檢查：錄製中且到時間就截圖 */
        private void maybeCapture() {
            if (!recording) return;
            long now = System.currentTimeMillis();
            if (now - lastSnap < INTERVAL_MS) return;
            lastSnap = now;
            Minecraft mc = Minecraft.getInstance();
            if (mc.getMainRenderTarget() == null) return;
            try {
                NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                int sw = img.getWidth(), sh = img.getHeight();
                int dw = Math.max(2, sw / 3), dh = Math.max(2, sh / 3);
                final int idx = ++frameCount;
                final String sess = session;
                NativeImage small = new NativeImage(dw, dh, true);
                for (int yy = 0; yy < dh; yy++) {
                    for (int xx = 0; xx < dw; xx++) {
                        small.setPixelRGBA(xx, yy, img.getPixelRGBA(xx * 3, yy * 3));
                    }
                }
                img.close();
                Thread t = new Thread(() -> {
                    try {
                        Path dir = Paths.dir("recordings").resolve(sess);
                        Files.createDirectories(dir);
                        BufferedImage bi = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);
                        for (int yy = 0; yy < dh; yy++) {
                            for (int xx = 0; xx < dw; xx++) {
                                int abgr = small.getPixelRGBA(xx, yy);
                                int a = (abgr >>> 24) & 0xFF, bl = (abgr >>> 16) & 0xFF;
                                int gr = (abgr >>> 8) & 0xFF, rd = abgr & 0xFF;
                                bi.setRGB(xx, yy, (a << 24) | (rd << 16) | (gr << 8) | bl);
                            }
                        }
                        ImageIO.write(bi, "png", dir.resolve(String.format("frame_%05d.png", idx)).toFile());
                    } catch (IOException ignored) {
                    } finally {
                        small.close();
                    }
                }, "mcphone-ultra-rec");
                t.setDaemon(true);
                t.start();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onOpen() {
            reload();
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            maybeCapture();

            Ui.textClipped(c, "⏺ 螢幕錄製", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int by = y + 13, bh = 12;
            if (clickOn(x + 1, by, 40, bh)) {
                if (recording) stop(); else start();
            }
            Ui.button(c, x + 1, by, 40, bh, true, c.hovered(x + 1, by, 40, bh));
            Ui.buttonLabel(c, x + 1, by, 40, bh, recording ? "■ 停止" : "● 錄製", true);
            if (clickOn(x + 43, by, 40, bh)) reload();
            Ui.button(c, x + 43, by, 40, bh, true, c.hovered(x + 43, by, 40, bh));
            Ui.buttonLabel(c, x + 43, by, 40, bh, "重新整理", true);

            if (recording) {
                Ui.fill(g, x + 85, by + 2, 10, 8, 0xFFFF4444);
                Ui.textClipped(c, frameCount + " 幀", x + 98, by + 2, s.accentColor(), x, by, w, bh);
            }

            Ui.text(c, "錄影檔（" + videos.size() + "）", x + 3, y + 28, s.subtleColor());
            int listY = y + 42, listH = h - 42 - 16, rowH = 12;
            scroller.clamp(videos.size() * rowH, listH);
            int off = (int) scroller.offset();
            for (int i = 0; i < videos.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                Ui.textClipped(c, "🎬 " + videos.get(i), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, videos.size() * rowH, off);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            scroller.onWheel(amount, videos.size() * 12, 130);
            return true;
        }

        @Override
        public boolean onBack() {
            return false;
        }
    }
}
