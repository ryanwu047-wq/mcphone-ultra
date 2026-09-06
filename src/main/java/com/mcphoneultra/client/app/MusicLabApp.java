package com.mcphoneultra.client.app;

import com.mcphoneultra.client.audio.AudioEngine;
import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.gui.GuiGraphics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 音樂實驗室：載入 wav/mp3/aiff → 波形檢視 → 裁剪/拼接/增益/淡入淡出/反轉/變速/歸一化 → 匯出 WAV。
 * 音樂檔放 config/mcphone_ultra/music/，maimai 與音樂播放器共用同一個資料夾。
 */
public final class MusicLabApp extends BaseApp {

    public MusicLabApp() {
        super("musiclab", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new LabPage();
    }

    static final class LabPage extends ClickablePage {

        private final List<Path> files = new ArrayList<>();
        private String toast = "";
        private long toastUntil;
        private Path current;
        private AudioEngine.Samples samples;
        private double selA = -1, selB = -1;
        private String pickConcat = null;   // 拼接选单是否打开
        private final List<Path> concatList = new ArrayList<>();
        private boolean concatReady;

        void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void reload() {
            files.clear();
            try (var stream = Files.list(Paths.dir("music"))) {
                stream.filter(p -> AudioEngine.isSupportedName(p.getFileName().toString()))
                        .forEach(files::add);
            } catch (IOException ignored) {
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
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

            if (current == null) {
                renderLibrary(c, x, y, w, h);
            } else if (pickConcat != null) {
                renderConcatPick(c, x, y, w, h);
            } else {
                renderEditor(c, x, y, w, h);
            }
            renderToast(c, x, y, w, h);
        }

        private void renderLibrary(PhoneCanvas c, int x, int y, int w, int h) {
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.text(c, "♪ 音樂實驗室", x + 3, y + 2, s.titleColor());
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());
            Ui.textClipped(c, "把 wav/mp3 丟進 config/mcphone_ultra/music/", x + 3, y + 13, s.subtleColor(), x, y, w, 24);

            int listY = y + 26;
            int rowH = 14;
            int listH = h - 26 - 16;
            for (int i = 0; i < files.size(); i++) {
                int ry = listY + i * rowH;
                if (ry + rowH > y + h - 14) break;
                Path p = files.get(i);
                boolean hover = c.hovered(x, ry, w, rowH);
                if (hover) Ui.fill(g, x, ry, w, rowH, s.pressedOverlay());
                Ui.textClipped(c, "♪ " + p.getFileName(), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
                if (clickOn(x, ry, w - 30, rowH)) {
                    load(p);
                }
                if (clickOn(x + w - 30, ry, 30, rowH)) {
                    AudioEngine.Player pl = new AudioEngine.Player();
                    AudioEngine.load(p).ifPresent(pl::play);
                }
                Ui.button(c, x + w - 30, ry, 30, rowH, true, c.hovered(x + w - 30, ry, 30, rowH));
                Ui.buttonLabel(c, x + w - 30, ry, 30, rowH, "試聽", true);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
        }

        private void load(Path p) {
            Optional<AudioEngine.Samples> r = AudioEngine.load(p);
            if (r.isEmpty()) {
                toast("無法解碼（OGG 不支援，請用 wav/mp3/aiff）");
                return;
            }
            current = p;
            samples = r.get();
            selA = -1;
            selB = -1;
            toast(AudioEngine.fmt(samples.seconds()) + " / " + samples.channels() + "ch");
        }

        private void renderEditor(PhoneCanvas c, int x, int y, int w, int h) {
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.textClipped(c, "♪ " + current.getFileName(), x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            // 波形区
            int wy = y + 13;
            int wh = 56;
            Ui.fill(g, x, wy, w, wh, 0xFF101418);
            Ui.border(c, x, wy, w, wh, s.buttonDisabledColor());
            if (samples != null) {
                drawWave(g, x + 1, wy + 1, w - 2, wh - 2);
                // 选区
                if (selA >= 0 && selB >= 0) {
                    int ax = x + 1 + (int) (selA / samples.seconds() * (w - 2));
                    int bx = x + 1 + (int) (selB / samples.seconds() * (w - 2));
                    int lo = Math.min(ax, bx), hi = Math.max(ax, bx);
                    Ui.fill(g, lo, wy + 1, hi - lo, wh - 2, 0x33FFD54D);
                }
                // 播放头
                double pos = player.positionSec();
                int px = x + 1 + (int) (pos / samples.seconds() * (w - 2));
                Ui.vline(g, px, wy + 1, wy + wh - 1, 0xFFFFFFFF);
                Ui.textClipped(c, AudioEngine.fmt(pos) + " / " + AudioEngine.fmt(samples.seconds()),
                        x + 3, wy + wh - 9, s.titleColor(), x, wy, w, wh);
            }

            // 第一行工具
            int by = wy + wh + 2;
            int bh = 13;
            int bw = (w - 4) / 5;
            buttonRow(c, x, by, bw, bh, 0, "設A", () -> { selA = player.positionSec(); toast("A=" + AudioEngine.fmt(selA)); });
            buttonRow(c, x, by, bw, bh, 1, "設B", () -> { selB = player.positionSec(); toast("B=" + AudioEngine.fmt(selB)); });
            buttonRow(c, x, by, bw, bh, 2, "A-B裁", () -> {
                if (selA < 0 || selB < 0) { toast("先設 A 與 B"); return; }
                samples = AudioEngine.trim(samples, Math.min(selA, selB), Math.max(selA, selB));
                toast("已裁剪 " + AudioEngine.fmt(samples.seconds()));
            });
            buttonRow(c, x, by, bw, bh, 3, "拼接", () -> pickConcat = "1");
            buttonRow(c, x, by, bw, bh, 4, "增益+", () -> samples = AudioEngine.gain(samples, 3));

            int by2 = by + bh + 1;
            buttonRow(c, x, by2, bw, bh, 0, "增益-", () -> samples = AudioEngine.gain(samples, -3));
            buttonRow(c, x, by2, bw, bh, 1, "淡變", () -> samples = AudioEngine.fade(samples, 0.5, 0.5));
            buttonRow(c, x, by2, bw, bh, 2, "反轉", () -> samples = AudioEngine.reverse(samples));
            buttonRow(c, x, by2, bw, bh, 3, "變速", () -> samples = AudioEngine.speed(samples, 1.5));
            buttonRow(c, x, by2, bw, bh, 4, "歸一", () -> samples = AudioEngine.normalize(samples, -1));

            int by3 = by2 + bh + 1;
            buttonRow(c, x, by3, bw, bh, 0, "播放", () -> player.play(samples));
            buttonRow(c, x, by3, bw, bh, 1, "暫停", () -> player.pause());
            buttonRow(c, x, by3, bw, bh, 2, "停止", () -> player.stop());
            buttonRow(c, x, by3, bw, bh, 3, "存WAV", () -> {
                Path out = Paths.file("music", Paths.safeName(stripExt(current.getFileName().toString()) + "_編輯.wav"));
                try {
                    AudioEngine.saveWav(out, samples);
                    toast("已存 " + out.getFileName());
                } catch (IOException e) {
                    toast("寫入失敗");
                }
            });
            buttonRow(c, x, by3, bw, bh, 4, "返回", () -> { player.stop(); current = null; reload(); });

            Ui.textClipped(c, "點波形可跳轉", x + 3, by3 + bh + 2, s.subtleColor(), x, y, w, h);
        }

        private final AudioEngine.Player player = new AudioEngine.Player();

        private void buttonRow(PhoneCanvas c, int x, int by, int bw, int bh, int idx, String label, Runnable action) {
            int bx = x + 1 + idx * (bw + 1);
            if (clickOn(bx, by, bw, bh)) action.run();
            Ui.button(c, bx, by, bw, bh, true, c.hovered(bx, by, bw, bh));
            Ui.buttonLabel(c, bx, by, bw, bh, label, true);
        }

        private void drawWave(GuiGraphics g, int wx, int wy, int ww, int wh) {
            float[] data = samples.data();
            int ch = samples.channels();
            int cols = Math.max(1, ww);
            int half = wh / 2 - 2;
            for (int col = 0; col < cols; col++) {
                int start = (int) ((long) col * samples.frames() / cols) * ch;
                int end = (int) ((long) (col + 1) * samples.frames() / cols) * ch;
                float min = 0, max = 0;
                for (int i = start; i < end && i < data.length; i++) {
                    float v = data[i];
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                int y0 = wy + half - (int) (max * half);
                int y1 = wy + half - (int) (min * half);
                Ui.vline(g, wx + col, y0, Math.max(y0 + 1, y1), 0xFF4FC3F7);
            }
            Ui.hline(g, wx, wx + ww, wy + half, 0xFF2A3A44);
        }

        private void renderConcatPick(PhoneCanvas c, int x, int y, int w, int h) {
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.text(c, "選擇要接在後面的音樂", x + 3, y + 2, s.titleColor());
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());
            int rowH = 14;
            int listY = y + 14;
            for (int i = 0; i < files.size(); i++) {
                Path p = files.get(i);
                if (p.equals(current)) continue;
                int ry = listY + i * rowH;
                if (ry + rowH > y + h - 30) break;
                Ui.textClipped(c, "♪ " + p.getFileName(), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
                if (clickOn(x, ry, w, rowH)) {
                    AudioEngine.load(p).ifPresent(second -> {
                        samples = AudioEngine.concat(List.of(samples, second));
                        toast("已拼接 " + AudioEngine.fmt(samples.seconds()));
                    });
                    pickConcat = null;
                }
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            if (clickOn(x + 30, y + h - 28, w - 60, 14)) pickConcat = null;
            Ui.button(c, x + 30, y + h - 28, w - 60, 14, true, c.hovered(x + 30, y + h - 28, w - 60, 14));
            Ui.buttonLabel(c, x + 30, y + h - 28, w - 60, 14, "取消", true);
        }

        private static String stripExt(String name) {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return false;
        }

        @Override
        public void onClose() {
            player.stop();
        }

        private void renderToast(PhoneCanvas c, int x, int y, int w, int h) {
            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                int tw = c.font().width(toast) + 8;
                int tx = x + (w - tw) / 2;
                int ty = y + h - 18;
                Ui.fill(c.graphics(), tx, ty, tw, 12, 0xE0202830);
                Ui.text(c, toast, tx + 4, ty + 1, c.style().titleColor());
            }
        }
    }
}
