package com.mcphoneultra.client.app;

import com.mcphoneultra.client.audio.AudioEngine;
import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Ui;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 音樂外放：在電腦喇叭播（JavaSound），同時按節拍向附近玩家廣播「音符盒」音效 ——
 * 其他在遊戲世界裡的玩家也能聽到手機正在放的歌。
 */
public final class SpeakerApp extends BaseApp {

    public SpeakerApp() {
        super("speaker", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new SpeakerPage();
    }

    private static final class SpeakerPage extends ClickablePage {

        private final List<Path> files = new ArrayList<>();
        private final AudioEngine.Player player = new AudioEngine.Player();
        private Path current;
        private float[] beatTimes = new float[0];
        private int beatIdx;
        private long lastBeatMs;
        private volatile boolean broadcast;
        private String status = "";
        private final Object lock = new Object();

        private static final SoundEvent[] NOTES = {
                SoundEvents.NOTE_BLOCK_HARP.value(), SoundEvents.NOTE_BLOCK_BASS.value(),
                SoundEvents.NOTE_BLOCK_SNARE.value(), SoundEvents.NOTE_BLOCK_HAT.value(),
                SoundEvents.NOTE_BLOCK_BELL.value(), SoundEvents.NOTE_BLOCK_CHIME.value()};

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

            Ui.text(c, "📢 音樂外放", x + 3, y + 2, s.titleColor());
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            int listY = y + 14;
            int rowH = 14;
            for (int i = 0; i < files.size(); i++) {
                int ry = listY + i * rowH;
                if (ry + rowH > y + h - 62) break;
                Path p = files.get(i);
                boolean hover = c.hovered(x, ry, w, rowH);
                if (hover) Ui.fill(g, x, ry, w, rowH, s.pressedOverlay());
                Ui.textClipped(c, (p.equals(current) ? "📣 " : "♪ ") + p.getFileName(), x + 3, ry + 1,
                        p.equals(current) ? s.accentColor() : s.bodyColor(), x, ry, w, rowH);
                if (clickOn(x, ry, w, rowH)) {
                    AudioEngine.load(p).ifPresentOrElse(sm -> {
                        synchronized (lock) {
                            current = p;
                            player.play(sm);
                            beatTimes = detectBeats(sm);
                            beatIdx = 0;
                            lastBeatMs = System.currentTimeMillis();
                            broadcast = true;
                            status = "外放中：" + p.getFileName() + "（" + beatTimes.length + " 拍）";
                        }
                    }, () -> status = "解碼失敗");
                }
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }

            int by = y + h - 46;
            int bh = 16;
            int bw = (w - 8) / 3;
            if (clickOn(x + 2, by, bw, bh)) synchronized (lock) { player.play(player.current()); broadcast = true; }
            Ui.button(c, x + 2, by, bw, bh, true, c.hovered(x + 2, by, bw, bh));
            Ui.buttonLabel(c, x + 2, by, bw, bh, "開始外放", true);

            if (clickOn(x + 2 + bw + 2, by, bw, bh)) synchronized (lock) { broadcast = false; player.stop(); }
            Ui.button(c, x + 2 + bw + 2, by, bw, bh, true, c.hovered(x + 2 + bw + 2, by, bw, bh));
            Ui.buttonLabel(c, x + 2 + bw + 2, by, bw, bh, "停止", true);

            if (clickOn(x + 2 + (bw + 2) * 2, by, bw, bh)) synchronized (lock) { broadcast = false; player.stop(); current = null; }
            Ui.button(c, x + 2 + (bw + 2) * 2, by, bw, bh, true, c.hovered(x + 2 + (bw + 2) * 2, by, bw, bh));
            Ui.buttonLabel(c, x + 2 + (bw + 2) * 2, by, bw, bh, "返回", true);

            Ui.textClipped(c, status, x + 3, by + bh + 3, s.subtleColor(), x, y, w, h);

            tickBroadcast();
            renderToast(c, x, y, w, h);
        }

        /** 简单能量峰值检测：50ms 窗 RMS 超过整体 1.3 倍记一拍。 */
        private static float[] detectBeats(AudioEngine.Samples sm) {
            int rate = sm.sampleRate();
            int win = rate / 20;
            List<Float> beats = new ArrayList<>();
            float[] data = sm.data();
            int ch = sm.channels();
            double rmsSum = 0;
            int nWin = 0;
            for (int i = 0; i + win < data.length; i += win) {
                double sum = 0;
                for (int j = i; j < i + win; j += ch) {
                    float v = data[j];
                    sum += v * v;
                }
                double rms = Math.sqrt(sum / (win / ch));
                rmsSum += rms;
                nWin++;
            }
            double avg = nWin == 0 ? 1 : rmsSum / nWin;
            for (int i = 0; i + win < data.length; i += win) {
                double sum = 0;
                for (int j = i; j < i + win; j += ch) {
                    float v = data[j];
                    sum += v * v;
                }
                double rms = Math.sqrt(sum / (win / ch));
                if (rms > avg * 1.25) {
                    beats.add((float) i / rate);
                }
            }
            float[] out = new float[beats.size()];
            for (int i = 0; i < out.length; i++) out[i] = beats.get(i);
            return out;
        }

        private long lastTick;
        private int tickNo;

        private void tickBroadcast() {
            if (!broadcast) return;
            long now = System.currentTimeMillis();
            if (now - lastTick < 30) return;
            lastTick = now;
            tickNo++;

            double pos = player.positionSec();
            while (beatIdx < beatTimes.length && beatTimes[beatIdx] + 0.12 < pos) beatIdx++;
            if (beatIdx < beatTimes.length && Math.abs(beatTimes[beatIdx] - pos) < 0.12) {
                playNote(beatTimes[beatIdx]);
                beatIdx++;
            }
            if (pos >= player.durationSec() && player.durationSec() > 0) {
                broadcast = false;
                status = "播放完畢";
            }
        }

        private void playNote(double sec) {
            var mc = Minecraft.getInstance();
            var playerEnt = mc.player;
            if (playerEnt == null || mc.level == null) return;
            int note = tickNo % NOTES.length;
            float pitch = 0.5f + (float) ((sec * 2) % 2.0) * 0.5f;
            BlockPos pos = playerEnt.blockPosition();
            mc.level.playSound(null, pos.getX(), pos.getY(), pos.getZ(),
                    NOTES[note], SoundSource.RECORDS, 1.6f, pitch);
        }

        private void renderToast(PhoneCanvas c, int x, int y, int w, int h) {
            if (broadcast && current != null) {
                Ui.textClipped(c, "🔊 世界廣播中…", x + 3, y + h - 12, c.style().accentColor(), x, y, w, h);
            }
        }

        @Override
        public void onClose() {
            synchronized (lock) {
                broadcast = false;
                player.stop();
            }
        }
    }
}
