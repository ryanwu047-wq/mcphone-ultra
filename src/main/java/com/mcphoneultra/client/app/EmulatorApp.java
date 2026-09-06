package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.ui.Scroller;
import com.mcphoneultra.client.ui.Ui;
import com.mcphoneultra.client.util.Images;
import com.mcphoneultra.client.util.JsEmu;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 遊戲機模擬器：NES（jsnes）/ GB（jsGB）/ GBA（gbajs2）。
 * ROM 放 config/mcphone/ultra/files/roms/{nes,gb,gba}/（可直接放 .zip，會取第一個 ROM 檔）。
 * GBA 可選放 bios.bin 到 roms/gba/ 以支援 BIOS 遊戲。
 */
public final class EmulatorApp extends BaseApp {

    public EmulatorApp() {
        super("emulator", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new EmuPage();
    }

    private static final class EmuPage extends ClickablePage {
        private JsEmu.System system = JsEmu.System.NES;
        private final List<String> roms = new ArrayList<>();
        private final Scroller scroller = new Scroller();
        private JsEmu emu;
        private Images.Tex frameTex;
        private byte[] lastFrameRef;
        private final List<Images.Tex> staleTex = new ArrayList<>();
        private boolean playing;
        private long lastFrameMs;
        private String toast = "";
        private long toastUntil;

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2000;
        }

        private void reload() {
            roms.clear();
            Path dir = romDir();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> isRomFile(p.getFileName().toString()))
                        .map(p -> p.getFileName().toString())
                        .forEach(roms::add);
            } catch (IOException ignored) {
            }
            roms.sort(Comparator.naturalOrder());
            scroller.reset();
        }

        private Path romDir() {
            return Paths.dir("files", "roms", system.name().toLowerCase(Locale.ROOT));
        }

        private static boolean isRomFile(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            return n.endsWith(".nes") || n.endsWith(".gb") || n.endsWith(".gbc")
                    || n.endsWith(".gba") || n.endsWith(".zip") || n.endsWith(".7z")
                    || n.endsWith(".bin");
        }

        private void setSystem(JsEmu.System s) {
            if (system == s) return;
            stopGame();
            system = s;
            reload();
        }

        private void play(String file) {
            try {
                byte[] rom = readRom(file);
                if (rom == null || rom.length == 0) {
                    toast("讀不到 ROM（zip 內沒有對應檔案？）");
                    return;
                }
                stopGame();
                emu = JsEmu.create(system);
                emu.loadRom(rom);
                playing = true;
                lastFrameMs = 0;
                toast("載入 " + file);
            } catch (IOException e) {
                toast("模擬器啟動失敗：" + e.getMessage());
            } catch (Throwable t) {
                toast("啟動失敗：" + (t.getMessage() == null ? t.toString() : t.getMessage()));
            }
        }

        private byte[] readRom(String file) {
            Path p = romDir().resolve(file);
            String n = file.toLowerCase(Locale.ROOT);
            try {
                if (n.endsWith(".zip")) {
                    try (ZipFile zf = new ZipFile(p.toFile())) {
                        var entries = zf.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry e = entries.nextElement();
                            String en = e.getName().toLowerCase(Locale.ROOT);
                            if (en.endsWith(".nes") || en.endsWith(".gb") || en.endsWith(".gbc")
                                    || en.endsWith(".gba") || en.endsWith(".bin")) {
                                if (e.getSize() > 64 * 1024 * 1024) break;
                                try (var in = zf.getInputStream(e)) {
                                    return in.readAllBytes();
                                }
                            }
                        }
                    }
                    return null;
                }
                return Files.readAllBytes(p);
            } catch (IOException e) {
                return null;
            }
        }

        private void stopGame() {
            playing = false;
            if (emu != null) {
                emu.close();
                emu = null;
            }
            releaseFrameTex();
        }

        private void releaseFrameTex() {
            if (frameTex != null) {
                Images.release(frameTex);
                frameTex = null;
            }
            for (Images.Tex t : staleTex) Images.release(t);
            staleTex.clear();
        }

        @Override
        public void onOpen() {
            reload();
        }

        @Override
        public void onClose() {
            stopGame();
        }

        @Override
        public void render(PhoneCanvas c) {
            int x = c.x(), y = c.y(), w = c.width(), h = c.height();
            var s = c.style();
            GuiGraphics g = c.graphics();
            Ui.fill(g, x, y, w, h, s.screenBackground());

            if (playing && emu != null) {
                renderGame(c, s, g);
                return;
            }

            Ui.textClipped(c, "🕹 模擬器", x + 3, y + 2, s.titleColor(), x, y, w, 12);
            Ui.hline(g, x, x + w, y + 11, s.buttonDisabledColor());

            String[] names = { "NES", "GB", "GBA" };
            int bw = (w - 4) / 3;
            for (int i = 0; i < 3; i++) {
                int bx = x + 2 + i * bw;
                if (clickOn(bx, y + 13, bw - 2, 12)) setSystem(JsEmu.System.values()[i]);
                boolean active = system.ordinal() == i;
                Ui.button(c, bx, y + 13, bw - 2, 12, true, active || c.hovered(bx, y + 13, bw - 2, 12));
                Ui.buttonLabel(c, bx, y + 13, bw - 2, 12, names[i], true);
            }
            if (clickOn(x + w - 34, y + 27, 32, 12)) reload();
            Ui.button(c, x + w - 34, y + 27, 32, 12, true, c.hovered(x + w - 34, y + 27, 32, 12));
            Ui.buttonLabel(c, x + w - 34, y + 27, 32, 12, "刷新", true);

            Ui.textClipped(c, "ROM：files/roms/" + system.name().toLowerCase(Locale.ROOT) + "/",
                    x + 3, y + 28, s.subtleColor(), x, y, w, 12);

            int listY = y + 42, listH = h - 42 - 16, rowH = 12;
            scroller.clamp(roms.size() * rowH, listH);
            int off = (int) scroller.offset();
            for (int i = 0; i < roms.size(); i++) {
                int ry = listY + i * rowH - off;
                if (ry + rowH < listY || ry > listY + listH) continue;
                if (clickOn(x, ry, w - 2, rowH)) play(roms.get(i));
                if (c.hovered(x, ry, w - 2, rowH)) Ui.fill(g, x, ry, w - 2, rowH, s.pressedOverlay());
                Ui.textClipped(c, "🎮 " + roms.get(i), x + 3, ry + 1, s.bodyColor(), x, ry, w, rowH);
                Ui.hline(g, x + 2, x + w - 2, ry + rowH - 1, s.buttonDisabledColor());
            }
            Ui.scrollbar(c, x + w - 3, listY, listH, roms.size() * rowH, off);

            if (System.currentTimeMillis() < toastUntil && !toast.isEmpty()) {
                Ui.drawCentered(c, toast, x, y + h - 14, w, 12, s.titleColor());
            }
        }

        private void renderGame(PhoneCanvas c, PhoneStyle s, GuiGraphics g) {
            long now = System.currentTimeMillis();
            if (now - lastFrameMs >= 16) {
                lastFrameMs = now;
                emu.frame();
            }
            byte[] fb = emu.frameBytes();
            if (fb != null && fb.length >= emu.width() * emu.height() * 4) {
                if (frameTex == null || fb != lastFrameRef) {
                    if (frameTex != null) {
                        Images.release(frameTex);
                        frameTex = null;
                    }
                    frameTex = uploadFrame(fb);
                    lastFrameRef = fb;
                }
                if (frameTex != null) {
                    int vw = c.width() - 4, vh = c.height() - 4;
                    float scale = Math.min(vw / (float) frameTex.w(), vh / (float) frameTex.h());
                    int tw = (int) (frameTex.w() * scale), th = (int) (frameTex.h() * scale);
                    int tx = c.x() + (vw - tw) / 2, ty = c.y() + (vh - th) / 2;
                    Ui.fill(g, c.x(), c.y(), c.width(), c.height(), 0xFF000000);
                    g.blit(frameTex.loc(), tx, ty, tw, th, 0, 0, frameTex.w(), frameTex.h(), frameTex.w(), frameTex.h());
                }
            }

            String err = emu.error();
            if (!err.isEmpty()) {
                Ui.drawCentered(c, "模擬器錯誤：" + err, c.x(), c.y() + 20, c.width(), 40, 0xFFFF6666);
                Ui.drawCentered(c, "（返回重選 ROM）", c.x(), c.y() + 40, c.width(), 30, 0xFFFF6666);
            }

            // 底部：暫停/返回 + 虛擬按鍵
            int by = c.y() + c.height() - 13;
            if (clickOn(c.x(), by, 34, 12)) {
                playing = false;
                stopGame();
                reload();
            }
            Ui.button(c, c.x(), by, 34, 12, true, c.hovered(c.x(), by, 34, 12));
            Ui.buttonLabel(c, c.x(), by, 34, 12, "← ROM 列表", true);

            if (clickOn(c.x() + 36, by, 26, 12)) {
                if (emu.isRomLoaded()) {
                    emu.close();
                    emu = null;
                    playing = false;
                    releaseFrameTex();
                    reload();
                }
            }
            Ui.button(c, c.x() + 36, by, 26, 12, true, c.hovered(c.x() + 36, by, 26, 12));
            Ui.buttonLabel(c, c.x() + 36, by, 26, 12, "關閉", true);

            Ui.textClipped(c, "鍵盤：方向/Z·X·A·S/Enter=Start/Shift=Select",
                    c.x() + 66, by + 1, s.subtleColor(), c.x(), by, c.width(), 12);
        }

        private Images.Tex uploadFrame(byte[] rgba) {
            int w = emu.width(), h = emu.height();
            NativeImage img = new NativeImage(w, h, true);
            for (int yy = 0; yy < h; yy++) {
                int row = yy * w * 4;
                for (int xx = 0; xx < w; xx++) {
                    int i = row + xx * 4;
                    int r = rgba[i] & 0xFF, g2 = rgba[i + 1] & 0xFF;
                    int b = rgba[i + 2] & 0xFF, a = rgba[i + 3] & 0xFF;
                    img.setPixelRGBA(xx, yy, (a << 24) | (b << 16) | (g2 << 8) | r);
                }
            }
            Images.Tex tex = Images.upload(img, "emu");
            if (frameTex != null) {
                staleTex.add(frameTex);
                if (staleTex.size() > 3) {
                    Images.release(staleTex.remove(0));
                }
            }
            return tex;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (playing) return true;
            scroller.onWheel(amount, roms.size() * 12, 130);
            return true;
        }

        @Override
        public boolean capturesKeyboard() {
            return playing;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (!playing || emu == null) return false;
            Integer code = keyCode(key);
            if (code != null) {
                emu.key(1, code, true);
                return true;
            }
            return true;
        }

        @Override
        public boolean keyReleased(int key, int scan, int mods) {
            if (!playing || emu == null) return false;
            Integer code = keyCode(key);
            if (code != null) {
                emu.key(1, code, false);
                return true;
            }
            return true;
        }

        /** 鍵盤 → 模擬器按鍵（DOM keyCode）。 */
        private Integer keyCode(int glfw) {
            int code;
            switch (glfw) {
                case GLFW.GLFW_KEY_UP -> code = 38;
                case GLFW.GLFW_KEY_DOWN -> code = 40;
                case GLFW.GLFW_KEY_LEFT -> code = 37;
                case GLFW.GLFW_KEY_RIGHT -> code = 39;
                case GLFW.GLFW_KEY_Z -> code = 90;
                case GLFW.GLFW_KEY_X -> code = 88;
                case GLFW.GLFW_KEY_A -> code = 65;
                case GLFW.GLFW_KEY_S -> code = 83;
                case GLFW.GLFW_KEY_ENTER -> code = 13;
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> code = 32;
                case GLFW.GLFW_KEY_SPACE -> code = 32;
                case GLFW.GLFW_KEY_TAB -> code = 220;
                default -> {
                    return null;
                }
            }
            return code;
        }

        @Override
        public boolean onBack() {
            if (playing) {
                playing = false;
                stopGame();
                reload();
                return true;
            }
            return false;
        }
    }
}
