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

/** 音樂播放器：播放 config/mcphone_ultra/music/ 下的音樂，進度條點擊跳轉。 */
public final class MusicPlayerApp extends BaseApp {

    public MusicPlayerApp() {
        super("musicplayer", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new PlayerPage();
    }

    private static final class PlayerPage extends ClickablePage {

        private final List<Path> files = new ArrayList<>();
        private final AudioEngine.Player player = new AudioEngine.Player();
        private Path current;
        private String toast = "";
        private long toastUntil;

        private void reload() {
            files.clear();
            try (var stream = Files.list(Paths.dir("music"))) {
                stream.filter(p -> AudioEngine.isSupportedName(p.getFileName().toString()))
                        .forEach(files::add);
            } catch (IOException ignored) {
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        }

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
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

            Ui.text(c, "♫ 音樂播放器", x + 3, y + 2, s.titleColor());
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int listY = y + 14;
            int rowH = 14;
            for (int i = 0; i < files.size(); i++) {
                int ry = listY + i * rowH;
                if (ry + rowH > y + h - 46) break;
                Path p = files.get(i);
                boolean hover = c.hovered(x, ry, w, rowH);
                if (hover) Ui.fill(g, x, ry, w, rowH, s.pressedOverlay());
                Ui.textClipped(c, (p.equals(current) ? "▶ " : "♪ ") + p.getFileName(), x + 3, ry + 1,
                        p.equals(current) ? s.accentColor() : s.bodyColor(), x, ry, w, rowH);
                if (clickOn(x, ry, w, rowH)) {
                    AudioEngine.load(p).ifPresentOrElse(sm -> {
                        current = p;
                        player.play(sm);
                        toast("播放 " + p.getFileName());
                    }, () -> toast("解碼失敗"));
                }
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }

            // 控制区
            int cy = y + h - 44;
            Ui.fill(g, x, cy, w, 44, 0xFF141A20);
            Ui.hline(g, x, x + w, cy, s.buttonDisabledColor());

            int bw = (w - 10) / 4;
            button(c, x + 2, cy + 2, bw, 16, "播放", () -> {
                if (current == null && !files.isEmpty()) {
                    AudioEngine.load(files.get(0)).ifPresent(sm -> { current = files.get(0); player.play(sm); });
                } else if (current != null) {
                    if (player.hasTrack()) player.resume(); else AudioEngine.load(current).ifPresent(player::play);
                }
            });
            button(c, x + 2 + (bw + 2), cy + 2, bw, 16, "暫停", player::pause);
            button(c, x + 2 + (bw + 2) * 2, cy + 2, bw, 16, "停止", player::stop);
            button(c, x + 2 + (bw + 2) * 3, cy + 2, bw, 16, "返回", () -> { player.stop(); current = null; });

            // 进度条（点击跳转）
            int px = x + 2, py = cy + 21, pw = w - 4, ph = 9;
            Ui.fill(g, px, py, pw, ph, s.buttonColor());
            double dur = player.durationSec();
            double pos = player.positionSec();
            if (dur > 0) {
                int fillW = (int) (pw * Math.min(1, pos / dur));
                Ui.fill(g, px, py, fillW, ph, s.accentColor());
                if (clickOn(px, py, pw, ph)) {
                    double frac = (clickX - px) / (double) pw;
                    player.seek(Math.max(0, Math.min(dur, frac * dur)));
                }
            }
            Ui.textClipped(c, AudioEngine.fmt(pos) + " / " + AudioEngine.fmt(dur), px, py + ph + 1,
                    s.subtleColor(), x, cy, w, 44 - ph);
            Ui.textClipped(c, current == null ? "" : current.getFileName().toString(), px, py - 1, s.titleColor(), x, cy, w, 44);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.textClipped(c, toast, x + 3, y + h - 12, s.titleColor(), x, y, w, h);
            }
        }

        private int clickX;

        private void button(PhoneCanvas c, int bx, int by, int bw, int bh, String label, Runnable action) {
            if (clickOn(bx, by, bw, bh)) action.run();
            Ui.button(c, bx, by, bw, bh, true, c.hovered(bx, by, bw, bh));
            Ui.buttonLabel(c, bx, by, bw, bh, label, true);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            clickX = (int) mx;
            return super.mouseClicked(mx, my, button);
        }

        @Override
        public void onClose() {
            player.stop();
        }
    }
}
